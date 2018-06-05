package coop.rchain.rholang.interpreter

import cats.implicits._
import cats.{Applicative, Monad}
import coop.rchain.models.Channel.ChannelInstance._
import coop.rchain.models.Connective.ConnectiveInstance._
import coop.rchain.models.Expr.ExprInstance._
import coop.rchain.models.Var.VarInstance._
import coop.rchain.models._
import coop.rchain.rholang.interpreter.errors.{InterpreterErrorsM, SubstituteError}
import coop.rchain.rholang.interpreter.implicits._
import errors._

trait Substitute[M[_], A] {
  def substitute(term: A)(implicit depth: Int, env: Env[Par]): M[A]
  def substituteNoSort(term: A)(implicit depth: Int, env: Env[Par]): M[A]
}

object Substitute {

  def substitute2[M[_]: Monad, A, B, C](termA: A, termB: B)(f: (A, B) => C)(
      implicit evA: Substitute[M, A],
      evB: Substitute[M, B],
      depth: Int,
      env: Env[Par]): M[C] =
    (evA.substitute(termA), evB.substitute(termB)).mapN(f)

  def substituteNoSort2[M[_]: Monad, A, B, C](termA: A, termB: B)(f: (A, B) => C)(
      implicit evA: Substitute[M, A],
      evB: Substitute[M, B],
      depth: Int,
      env: Env[Par]): M[C] =
    (evA.substituteNoSort(termA), evB.substituteNoSort(termB)).mapN(f)

  def apply[M[_], A](implicit ev: Substitute[M, A]): Substitute[M, A] = ev

  def maybeSubstitute[M[+ _]: InterpreterErrorsM](term: Var)(implicit depth: Int,
                                                             env: Env[Par]): M[Either[Var, Par]] =
    if (depth != 0)
      Applicative[M].pure(Left[Var, Par](term)) //scalac is not helping here
    else
      term.varInstance match {
        case BoundVar(index) =>
          env.get(index) match {
            case Some(par) => Applicative[M].pure(Right(par))
            case None =>
              Applicative[M].pure(Left[Var, Par](BoundVar(index))) //scalac is not helping here
          }
        case _ =>
          interpreterErrorM[M].raiseError(SubstituteError(s"Illegal Substitution [$term]"))
      }

  def maybeSubstitute[M[_]: InterpreterErrorsM](term: EVar)(implicit depth: Int,
                                                            env: Env[Par]): M[Either[EVar, Par]] =
    maybeSubstitute[M](term.v.get).map {
      case Left(v)    => Left(EVar(v))
      case Right(par) => Right(par)
    }

  def maybeSubstitute[M[_]: InterpreterErrorsM](
      term: EEvalBody)(implicit depth: Int, env: Env[Par]): M[Either[Expr, Par]] =
    term.value.channelInstance match {
      case Quote(p) => substitutePar[M].substituteNoSort(p).map(Right(_))
      case ChanVar(v) =>
        maybeSubstitute[M](v).map {
          case Left(v)    => Left(Expr(EEvalBody(ChanVar(v))))
          case Right(par) => Right(par)
        }
    }

  def maybeSubstitute[M[_]: InterpreterErrorsM](
      term: VarRef)(implicit depth: Int, env: Env[Par]): M[Either[VarRef, Par]] =
    if (term.depth != depth)
      Applicative[M].pure(Left(term))
    else
      env.get(term.index) match {
        case Some(par) => Applicative[M].pure(Right(par))
        case None =>
          interpreterErrorM[M].raiseError(SubstituteError(s"Illegal VarRef [$term]"))
      }

  implicit def substituteQuote[M[_]: InterpreterErrorsM]: Substitute[M, Quote] =
    new Substitute[M, Quote] {
      override def substitute(term: Quote)(implicit depth: Int, env: Env[Par]): M[Quote] =
        substitutePar[M].substitute(term.value).map(Quote(_))
      override def substituteNoSort(term: Quote)(implicit depth: Int, env: Env[Par]): M[Quote] =
        substitutePar[M].substituteNoSort(term.value).map(Quote(_))
    }

  implicit def substituteBundle[M[_]: InterpreterErrorsM]: Substitute[M, Bundle] =
    new Substitute[M, Bundle] {
      import BundleOps._

      override def substitute(term: Bundle)(implicit depth: Int, env: Env[Par]): M[Bundle] =
        substitutePar[M].substitute(term.body.get).map { subBundle =>
          subBundle.singleBundle() match {
            case Some(value) => term.merge(value)
            case None        => term.copy(body = subBundle)
          }
        }
      override def substituteNoSort(term: Bundle)(implicit depth: Int, env: Env[Par]): M[Bundle] =
        substitutePar[M].substituteNoSort(term.body.get).map { subBundle =>
          subBundle.singleBundle() match {
            case Some(value) => term.merge(value)
            case None        => term.copy(body = subBundle)
          }
        }
    }

  implicit def substituteChannel[M[_]: InterpreterErrorsM]: Substitute[M, Channel] =
    new Substitute[M, Channel] {
      override def substituteNoSort(term: Channel)(implicit depth: Int, env: Env[Par]): M[Channel] =
        for {
          channelSubst <- term.channelInstance match {
                           case Quote(p) => substitutePar[M].substitute(p).map(Quote(_))
                           case ChanVar(v) =>
                             maybeSubstitute[M](v).map {
                               case Left(_v) => ChanVar(_v)
                               case Right(p) => Quote(p)
                             }
                         }
        } yield channelSubst
      override def substitute(term: Channel)(implicit depth: Int, env: Env[Par]): M[Channel] =
        for {
          channelSubst <- substituteNoSort(term)
          sortedChan   <- ChannelSortMatcher.sortMatch[M](channelSubst)
        } yield sortedChan.term
    }

  implicit def substitutePar[M[_]: InterpreterErrorsM]: Substitute[M, Par] =
    new Substitute[M, Par] {
      def subExp(exprs: Seq[Expr])(implicit depth: Int, env: Env[Par]): M[Par] =
        exprs.toList.reverse.foldM(VectorPar()) { (par, expr) =>
          expr.exprInstance match {
            case EVarBody(e) =>
              maybeSubstitute[M](e).map {
                case Left(_e)    => par.prepend(_e)
                case Right(_par) => _par ++ par
              }
            case e: EEvalBody =>
              maybeSubstitute[M](e).map {
                case Left(expr)  => par.prepend(expr)
                case Right(_par) => _par ++ par
              }
            case _ => substituteExpr[M].substituteNoSort(expr).map(par.prepend(_))
          }
        }

      def subConn(conns: Seq[Connective])(implicit depth: Int, env: Env[Par]): M[Par] =
        conns.toList.reverse.foldM(VectorPar()) { (par, conn) =>
          conn.connectiveInstance match {
            case VarRefBody(v) =>
              maybeSubstitute[M](v).map {
                case Left(_)       => par.prepend(conn)
                case Right(newPar) => newPar ++ par
              }
            case _ => substituteConnective[M].substituteNoSort(conn).map(par.prepend(_))
          }
        }

      override def substituteNoSort(term: Par)(implicit depth: Int, env: Env[Par]): M[Par] =
        for {
          exprs       <- subExp(term.exprs)
          connectives <- subConn(term.connectives)
          sends       <- term.sends.toList.traverse(substituteSend[M].substituteNoSort(_))
          bundles     <- term.bundles.toList.traverse(substituteBundle[M].substituteNoSort(_))
          receives    <- term.receives.toList.traverse(substituteReceive[M].substituteNoSort(_))
          news        <- term.news.toList.traverse(substituteNew[M].substituteNoSort(_))
          matches     <- term.matches.toList.traverse(substituteMatch[M].substituteNoSort(_))
          par = exprs ++
            connectives ++
            Par(
              exprs = Nil,
              sends = sends,
              bundles = bundles,
              receives = receives,
              news = news,
              matches = matches,
              ids = term.ids,
              connectives = Nil,
              locallyFree = term.locallyFree.until(env.shift),
              connectiveUsed = term.connectiveUsed
            )
        } yield par
      override def substitute(term: Par)(implicit depth: Int, env: Env[Par]): M[Par] =
        for {
          par       <- substituteNoSort(term)
          sortedPar <- ParSortMatcher.sortMatch[M](par)
        } yield sortedPar.term.get
    }

  implicit def substituteSend[M[_]: InterpreterErrorsM]: Substitute[M, Send] =
    new Substitute[M, Send] {
      override def substituteNoSort(term: Send)(implicit depth: Int, env: Env[Par]): M[Send] =
        for {
          channelsSub <- substituteChannel[M].substituteNoSort(term.chan.get)
          parsSub     <- term.data.toList.traverse(substitutePar[M].substituteNoSort(_))
          send = Send(
            chan = channelsSub,
            data = parsSub,
            persistent = term.persistent,
            locallyFree = term.locallyFree.until(env.shift),
            connectiveUsed = term.connectiveUsed
          )
        } yield send
      override def substitute(term: Send)(implicit depth: Int, env: Env[Par]): M[Send] =
        for {
          send       <- substituteNoSort(term)
          sortedSend <- SendSortMatcher.sortMatch[M](send)
        } yield sortedSend.term
    }

  implicit def substituteReceive[M[_]: InterpreterErrorsM]: Substitute[M, Receive] =
    new Substitute[M, Receive] {
      override def substituteNoSort(term: Receive)(implicit depth: Int, env: Env[Par]): M[Receive] =
        for {
          bindsSub <- term.binds.toList.traverse {
                       case ReceiveBind(patterns, Some(chan), rem, freeCount) =>
                         for {
                           subChannel <- substituteChannel[M].substituteNoSort(chan)
                           subPatterns <- patterns.toList.traverse(
                                           pattern =>
                                             substituteChannel[M]
                                               .substituteNoSort(pattern)(depth + 1, env))
                         } yield ReceiveBind(subPatterns, subChannel, rem, freeCount)
                     }
          bodySub <- substitutePar[M].substituteNoSort(term.body.get)(depth,
                                                                      env.shift(term.bindCount))
          rec = Receive(
            binds = bindsSub,
            body = bodySub,
            persistent = term.persistent,
            bindCount = term.bindCount,
            locallyFree = term.locallyFree.until(env.shift),
            connectiveUsed = term.connectiveUsed
          )
        } yield rec
      override def substitute(term: Receive)(implicit depth: Int, env: Env[Par]): M[Receive] =
        for {
          rec           <- substituteNoSort(term)
          sortedReceive <- ReceiveSortMatcher.sortMatch[M](rec)
        } yield sortedReceive.term

    }

  implicit def substituteNew[M[_]: InterpreterErrorsM]: Substitute[M, New] =
    new Substitute[M, New] {
      override def substituteNoSort(term: New)(implicit depth: Int, env: Env[Par]): M[New] =
        for {
          newSub <- substitutePar[M].substituteNoSort(term.p.get)(depth, env.shift(term.bindCount))
          neu    = New(term.bindCount, newSub, term.locallyFree.until(env.shift))
        } yield neu
      override def substitute(term: New)(implicit depth: Int, env: Env[Par]): M[New] =
        for {
          newSub      <- substituteNoSort(term)
          sortedMatch <- NewSortMatcher.sortMatch[M](newSub)
        } yield sortedMatch.term
    }

  implicit def substituteMatch[M[_]: InterpreterErrorsM]: Substitute[M, Match] =
    new Substitute[M, Match] {
      override def substituteNoSort(term: Match)(implicit depth: Int, env: Env[Par]): M[Match] =
        for {
          targetSub <- substitutePar[M].substituteNoSort(term.target.get)
          casesSub <- term.cases.toList.traverse {
                       case MatchCase(Some(_case), Some(_par), freeCount) =>
                         for {
                           par <- substitutePar[M].substituteNoSort(_par)(depth,
                                                                          env.shift(freeCount))
                           subCase <- substitutePar[M].substituteNoSort(_case)(depth + 1, env)
                         } yield MatchCase(subCase, par, freeCount)
                     }
          mat = Match(targetSub, casesSub, term.locallyFree.until(env.shift), term.connectiveUsed)
        } yield mat
      override def substitute(term: Match)(implicit depth: Int, env: Env[Par]): M[Match] =
        for {
          mat         <- substituteNoSort(term)
          sortedMatch <- MatchSortMatcher.sortMatch[M](mat)
        } yield sortedMatch.term
    }

  implicit def substituteExpr[M[_]: InterpreterErrorsM]: Substitute[M, Expr] =
    new Substitute[M, Expr] {
      private[this] def substituteDelegate(
          term: Expr,
          s1: Par => M[Par],
          s2: (Par, Par) => ((Par, Par) => Expr) => M[Expr])(implicit env: Env[Par]): M[Expr] =
        term.exprInstance match {
          case ENotBody(ENot(par)) => s1(par.get).map(ENot(_))
          case ENegBody(ENeg(par)) => s1(par.get).map(ENeg(_))
          case EMultBody(EMult(par1, par2)) =>
            s2(par1.get, par2.get)(EMult(_, _))
          case EDivBody(EDiv(par1, par2)) =>
            s2(par1.get, par2.get)(EDiv(_, _))
          case EPlusBody(EPlus(par1, par2)) =>
            s2(par1.get, par2.get)(EPlus(_, _))
          case EMinusBody(EMinus(par1, par2)) =>
            s2(par1.get, par2.get)(EMinus(_, _))
          case ELtBody(ELt(par1, par2)) =>
            s2(par1.get, par2.get)(ELt(_, _))
          case ELteBody(ELte(par1, par2)) =>
            s2(par1.get, par2.get)(ELte(_, _))
          case EGtBody(EGt(par1, par2)) =>
            s2(par1.get, par2.get)(EGt(_, _))
          case EGteBody(EGte(par1, par2)) =>
            s2(par1.get, par2.get)(EGte(_, _))
          case EEqBody(EEq(par1, par2)) =>
            s2(par1.get, par2.get)(EEq(_, _))
          case ENeqBody(ENeq(par1, par2)) =>
            s2(par1.get, par2.get)(ENeq(_, _))
          case EAndBody(EAnd(par1, par2)) =>
            s2(par1.get, par2.get)(EAnd(_, _))
          case EOrBody(EOr(par1, par2)) =>
            s2(par1.get, par2.get)(EOr(_, _))
          case EListBody(EList(ps, locallyFree, connectiveUsed, rem)) =>
            for {
              pss <- ps.toList
                      .traverse(p => s1(p))
              newLocallyFree = locallyFree.until(env.shift)
            } yield Expr(exprInstance = EListBody(EList(pss, newLocallyFree, connectiveUsed, rem)))

          case ETupleBody(ETuple(ps, locallyFree, connectiveUsed)) =>
            for {
              pss <- ps.toList
                      .traverse(p => s1(p))
              newLocallyFree = locallyFree.until(env.shift)
            } yield Expr(exprInstance = ETupleBody(ETuple(pss, newLocallyFree, connectiveUsed)))

          case ESetBody(ESet(ps, locallyFree, connectiveUsed)) =>
            for {
              pss <- ps.toList
                      .traverse(p => s1(p))
              newLocallyFree = locallyFree.until(env.shift)
            } yield Expr(exprInstance = ESetBody(ESet(pss, newLocallyFree, connectiveUsed)))

          case EMapBody(EMap(kvs, locallyFree, connectiveUsed)) =>
            for {
              kvps <- kvs.toList
                       .traverse {
                         case KeyValuePair(p1, p2) =>
                           for {
                             pk1 <- s1(p1.get)
                             pk2 <- s1(p2.get)
                           } yield KeyValuePair(pk1, pk2)
                       }
              newLocallyFree = locallyFree.until(env.shift)
            } yield Expr(exprInstance = EMapBody(EMap(kvps, newLocallyFree, connectiveUsed)))
          case g @ _ => Applicative[M].pure(term)
        }
      override def substitute(term: Expr)(implicit depth: Int, env: Env[Par]): M[Expr] =
        substituteDelegate(term, substitutePar[M].substitute, substitute2[M, Par, Par, Expr])
      override def substituteNoSort(term: Expr)(implicit depth: Int, env: Env[Par]): M[Expr] =
        substituteDelegate(term,
                           substitutePar[M].substituteNoSort,
                           substituteNoSort2[M, Par, Par, Expr])
    }

  implicit def substituteConnective[M[_]: InterpreterErrorsM]: Substitute[M, Connective] =
    new Substitute[M, Connective] {
      override def substituteNoSort(term: Connective)(implicit depth: Int,
                                                      env: Env[Par]): M[Connective] =
        term.connectiveInstance match {
          case ConnAndBody(ConnectiveBody(ps)) =>
            ps.toList
              .traverse(substitutePar[M].substituteNoSort(_))
              .map(ps => Connective(ConnAndBody(ConnectiveBody(ps))))
          case ConnOrBody(ConnectiveBody(ps)) =>
            ps.toList
              .traverse(substitutePar[M].substituteNoSort(_))
              .map(ps => Connective(ConnOrBody(ConnectiveBody(ps))))
          case ConnNotBody(p) =>
            substitutePar[M].substituteNoSort(p).map(p => Connective(ConnNotBody(p)))
        }
      override def substitute(term: Connective)(implicit depth: Int, env: Env[Par]): M[Connective] =
        for {
          con       <- substituteNoSort(term)
          sortedCon <- ConnectiveSortMatcher.sortMatch[M](con)
        } yield sortedCon.term
    }
}
