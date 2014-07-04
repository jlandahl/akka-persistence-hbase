package akka.persistence.hbase.common

import org.apache.hadoop.hbase.util.Bytes
import akka.persistence.hbase.journal.PluginPersistenceSettings

import scala.annotation.tailrec

case class RowKey(part: Long, persistenceId: String, sequenceNr: Long)(implicit val hBasePersistenceSettings: PluginPersistenceSettings) {

  def toBytes = Bytes.toBytes(toKeyString)

  def toKeyString = s"${padded(part, 3)}-$persistenceId-${padded(sequenceNr, 20)}"

  @inline def padded(l: Long, howLong: Int) =
    String.valueOf(l).reverse.padTo(howLong, "0").reverse.mkString

}

object RowKey {
  /**
   * Since we're salting (prefixing) the entries with selectPartition numbers,
   * we must use this pattern for scanning for "all messages for processorX"
   */
  def patternForProcessor(persistenceId: String)(implicit journalConfig: PluginPersistenceSettings) = s""".*-$persistenceId-.*"""

  def firstInPartition(persistenceId: String, partition: Long, fromSequenceNr: Long = 0)(implicit journalConfig: PluginPersistenceSettings) = {
    require(partition > 0, "partition must be > 0")
    require(partition <= journalConfig.partitionCount, "partition must be <= partitionCount")

    val lowerBoundAdjustedSeqNr =
      if (partition < fromSequenceNr)
        fromSequenceNr
      else if (partition == journalConfig.partitionCount)
        partition
      else
        selectPartition(partition)

      RowKey.apply(selectPartition(partition), persistenceId, lowerBoundAdjustedSeqNr)
  }

  def lastInPartition(persistenceId: String, targetPartition: Long, toSequenceNr: Long = Long.MaxValue)(implicit journalConfig: PluginPersistenceSettings) = {
    require(targetPartition > 0, s"partition must be > 0, ($targetPartition)")
    require(targetPartition <= journalConfig.partitionCount, s"partition must be <= partitionCount, ($targetPartition <!= ${journalConfig.partitionCount})")

    new RowKey(selectPartition(targetPartition)(journalConfig), persistenceId, lastSeqNrInPartition(targetPartition, toSequenceNr))
  }

  /** First key possible, similar to: `000-id-000000000000000000000` */
  def firstForPersistenceId(persistenceId: String)(implicit journalConfig: PluginPersistenceSettings) =
    RowKey(selectPartition(0), persistenceId, 0)

//  /**
//   * Last key prepared for Scan, similar to: `999-id-0000000121212`,
//   * where the 999 is adjusted such, that a full scan reaches where it has to, and not further.
//   */
//  def lastForProcessorScan(persistenceId: String, upToSequenceNr: Long)(implicit journalConfig: PluginPersistenceSettings) =
//      new RowKey(persistenceId, upToSequenceNr) {
//        val partitionCount = hBasePersistenceSettings.partitionCount
//
//        // todo this is wrong
//        override def toKeyString =
//          if (upToSequenceNr < partitionCount)
//            super.toKeyString
//          else
//            s"${padded(partitionCount - 1, 3)}-$persistenceId-${padded(sequenceNr, 20)}" // partitionCount - 1 because we're using "N modulo partitionCount", so if equal => 0
//  }

  /** Last key possible, similar to: `999-id-Long.MaxValue` */
  def lastForPersistenceId(persistenceId: String, toSequenceNr: Long = Long.MaxValue)(implicit journalConfig: PluginPersistenceSettings) =
    lastInPartition(persistenceId, selectPartition(journalConfig.partitionCount - 1), Long.MaxValue) // todo can be optimised a little, use toSequenceNr + bump it (because scan is exclusive)

  /** Used to avoid writing all data to the same region - see "hot region" problem */
  def selectPartition(sequenceNr: Long)(implicit journalConfig: PluginPersistenceSettings): Long =
    sequenceNr % journalConfig.partitionCount

  /** INTERNAL API */
  @tailrec private[hbase] def lastSeqNrInPartition(p: Long, i: Long): Long = if (i % p == 0) i else lastSeqNrInPartition(p, i - 1)

}