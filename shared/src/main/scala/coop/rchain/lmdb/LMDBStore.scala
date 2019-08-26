package coop.rchain.lmdb

import java.nio.ByteBuffer

import cats.implicits._
import cats.effect.Sync

import coop.rchain.shared.Resources.withResource

import org.lmdbjava.{CursorIterator, Dbi, Env, Txn}
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import coop.rchain.metrics.implicits._

import coop.rchain.metrics.Metrics

final case class LMDBStore[F[_]: Sync: Metrics](env: Env[ByteBuffer], dbi: Dbi[ByteBuffer])(
    implicit ms: Metrics.Source
) {

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  // TODO stop throwing exceptions
  private def withTxn[R](txn: Txn[ByteBuffer])(f: Txn[ByteBuffer] => R): R =
    try {
      val ret: R = f(txn)
      txn.commit()
      ret
    } catch {
      case NonFatal(ex) =>
        ex.printStackTrace()
        throw ex
    } finally {
      txn.close()
    }

  def withReadTxnF[R](f: Txn[ByteBuffer] => R): F[R] =
    Sync[F]
      .delay {
        withTxn(env.txnRead)(f)
      }
      .timer("lmdb.read")

  def withWriteTxnF[R](f: Txn[ByteBuffer] => R): F[R] =
    Sync[F]
      .delay {
        withTxn(env.txnWrite)(f)
      }
      .timer("lmdb.write")

  def get(key: ByteBuffer): F[Option[ByteBuffer]] =
    withReadTxnF { txn =>
      dbi.get(txn, key)
    }.map(v => Option(v))

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def put(key: ByteBuffer, value: ByteBuffer): F[Unit] =
    withWriteTxnF { txn =>
      if (!dbi.put(txn, key, value)) {
        throw new RuntimeException("was not able to put data")
      }
    }

  def iterate[R](f: Iterator[CursorIterator.KeyVal[ByteBuffer]] => R): F[R] =
    withReadTxnF { txn =>
      withResource(dbi.iterate(txn)) { iterator =>
        f(iterator.asScala)
      }
    }

  def drop: F[Unit] =
    withWriteTxnF { txn =>
      dbi.drop(txn)
    }

  def close(): F[Unit] =
    Sync[F].delay {
      env.close()
    }
}
