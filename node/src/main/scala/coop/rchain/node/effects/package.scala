package coop.rchain.node

import java.nio.file.Path

import scala.concurrent.duration._
import scala.io.Source
import scala.tools.jline.console._

import cats.effect.Timer
import cats.mtl._
import cats.Applicative

import coop.rchain.comm._
import coop.rchain.comm.discovery._
import coop.rchain.comm.rp._
import coop.rchain.comm.rp.Connect._
import coop.rchain.comm.transport._
import coop.rchain.metrics.Metrics
import coop.rchain.shared._

import monix.eval._
import monix.execution._
import monix.execution.atomic.AtomicAny

package object effects {

  def log: Log[Task] = Log.log

  def kademliaStore(id: NodeIdentifier)(
      implicit
      kademliaRPC: KademliaRPC[Task],
      metrics: Metrics[Task]
  ): KademliaStore[Task] = KademliaStore.table(id)

  def nodeDiscovery(id: NodeIdentifier)(
      implicit
      kademliaStore: KademliaStore[Task],
      kademliaRPC: KademliaRPC[Task]
  ): NodeDiscovery[Task] = NodeDiscovery.kademlia(id)

  def time(implicit timer: Timer[Task]): Time[Task] =
    new Time[Task] {
      def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
    }

  def kademliaRPC(networkId: String, timeout: FiniteDuration, allowPrivateAddresses: Boolean)(
      implicit
      scheduler: Scheduler,
      peerNodeAsk: PeerNodeAsk[Task],
      metrics: Metrics[Task]
  ): KademliaRPC[Task] = new GrpcKademliaRPC(networkId, timeout, allowPrivateAddresses)

  def transportClient(
      networkId: String,
      certPath: Path,
      keyPath: Path,
      maxMessageSize: Int,
      packetChunkSize: Int,
      folder: Path
  )(
      implicit scheduler: Scheduler,
      log: Log[Task],
      metrics: Metrics[Task]
  ): Task[TransportLayer[Task]] =
    Task.delay {
      val cert = Resources.withResource(Source.fromFile(certPath.toFile))(_.mkString)
      val key  = Resources.withResource(Source.fromFile(keyPath.toFile))(_.mkString)
      new GrpcTransportClient(networkId, cert, key, maxMessageSize, packetChunkSize, folder, 1000)
    }

  def consoleIO(consoleReader: ConsoleReader): ConsoleIO[Task] = new JLineConsoleIO(consoleReader)

  def rpConnections: Task[ConnectionsCell[Task]] =
    Cell.mvarCell[Task, Connections](Connections.empty)

  def rpConfState(conf: RPConf): MonadState[Task, RPConf] =
    new AtomicMonadState[Task, RPConf](AtomicAny(conf))

  def rpConfAsk(implicit state: MonadState[Task, RPConf]): ApplicativeAsk[Task, RPConf] =
    new DefaultApplicativeAsk[Task, RPConf] {
      val applicative: Applicative[Task] = Applicative[Task]
      def ask: Task[RPConf]              = state.get
    }

  def peerNodeAsk(implicit state: MonadState[Task, RPConf]): ApplicativeAsk[Task, PeerNode] =
    new DefaultApplicativeAsk[Task, PeerNode] {
      val applicative: Applicative[Task] = Applicative[Task]
      def ask: Task[PeerNode]            = state.get.map(_.local)
    }

  def messageQueueMonitor: Task[MessageQueueMonitor[Task]] =
    Cell
      .mvarCell[Task, Map[Long, ServerMessage]](Map.empty)
      .map { cell =>
        new MessageQueueMonitor[Task] {
          def added(id: Long, msg: ServerMessage): Task[Unit] = cell.modify(_ + (id -> msg))
          def consumed(id: Long): Task[Unit]                  = cell.modify(_ - id)
          def read: Task[Seq[ServerMessage]]                  = cell.read.map(_.toList.sortBy(_._1).map(_._2))
          def size: Task[Int]                                 = cell.read.map(_.size)
        }
      }
}
