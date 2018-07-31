package coop.rchain.models.rholang.sort
import coop.rchain.models.Expr.ExprInstance
import coop.rchain.models.Expr.ExprInstance.GBool
import coop.rchain.models._

trait Sortable[T] {
  def sortMatch(term: T): ScoredTerm[T]
}

object Sortable {
  def apply[T](implicit ev: Sortable[T]) = ev

  implicit val boolSortable: Sortable[GBool]            = BoolSortMatcher
  implicit val bundleSortable: Sortable[Bundle]         = BundleSortMatcher
  implicit val channelSortable: Sortable[Channel]       = ChannelSortMatcher
  implicit val connectiveSortable: Sortable[Connective] = ConnectiveSortMatcher
  implicit val exprSortable: Sortable[Expr]             = ExprSortMatcher
  implicit val groundSortable: Sortable[ExprInstance]   = GroundSortMatcher
  implicit val matchSortable: Sortable[Match]           = MatchSortMatcher
  implicit val newSortable: Sortable[New]               = NewSortMatcher
  implicit val parSortable: Sortable[Par]               = ParSortMatcher
  implicit val receiveSortable: Sortable[Receive]       = ReceiveSortMatcher
  implicit val sendSortable: Sortable[Send]             = SendSortMatcher
  implicit val varSortable: Sortable[Var]               = VarSortMatcher
}
