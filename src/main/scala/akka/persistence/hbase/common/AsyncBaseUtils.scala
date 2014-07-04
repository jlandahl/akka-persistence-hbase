package akka.persistence.hbase.common

import java.{util => ju}

import akka.persistence.hbase.journal.PluginPersistenceSettings
import akka.persistence.hbase.journal.RowTypeMarkers._
import org.apache.hadoop.hbase.util.Bytes
import org.hbase.async.{DeleteRequest, HBaseClient, KeyValue, PutRequest}

import scala.concurrent.{ExecutionContext, Future}

trait AsyncBaseUtils {

  def hBasePersistenceSettings: PluginPersistenceSettings

  def client: HBaseClient

  implicit val pluginDispatcher: ExecutionContext

  private lazy val Table = Bytes.toBytes(hBasePersistenceSettings.table)
  private lazy val Family = Bytes.toBytes(hBasePersistenceSettings.family)

  import akka.persistence.hbase.common.Columns._
  import akka.persistence.hbase.common.DeferredConversions._

  /** Used to avoid writing all data to the same region - see "hot region" problem */
  def selectPartition(sequenceNr: Long)(implicit journalConfig: PluginPersistenceSettings): Long =
    RowKey.selectPartition(sequenceNr)

  protected def isSnapshotRow(columns: Seq[KeyValue]): Boolean =
    ju.Arrays.equals(findColumn(columns, Marker).value, SnapshotMarkerBytes)

  protected def findColumn(columns: Seq[KeyValue], qualifier: Array[Byte]): KeyValue =
    columns find { kv =>
      ju.Arrays.equals(kv.qualifier, qualifier)
    } getOrElse {
      throw new RuntimeException(s"Unable to find [${Bytes.toString(qualifier)}}] field from: ${columns.map(kv => Bytes.toString(kv.qualifier))}")
    }

    protected def deleteRow(key: Array[Byte]): Future[Unit] = {
//      println(s"Permanently deleting row: ${Bytes.toString(key)}")
      executeDelete(key)
    }

    protected def markRowAsDeleted(key: Array[Byte]): Future[Unit] = {
//      println(s"Marking as deleted, for row: ${Bytes.toString(key)}")
      executePut(key, Array(Marker), Array(DeletedMarkerBytes))
    }

    protected def executeDelete(key: Array[Byte]): Future[Unit] = {
      val request = new DeleteRequest(Table, key)
      client.delete(request)
    }

    protected def executePut(key: Array[Byte], qualifiers: Array[Array[Byte]], values: Array[Array[Byte]]): Future[Unit] = {
      val request = new PutRequest(Table, key, Family, qualifiers, values)
      client.put(request)
    }

    /**
     * Sends the buffered commands to HBase. Does not guarantee that they "complete" right away.
     */
    def flushWrites() {
      client.flush()
    }

    protected def newScanner() = {
      val scanner = client.newScanner(Table)
      scanner.setFamily(Family)
      scanner
    }

}
