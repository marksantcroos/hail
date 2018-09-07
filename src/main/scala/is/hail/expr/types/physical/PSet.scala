package is.hail.expr.types.physical

import is.hail.annotations.{UnsafeUtils, _}
import is.hail.check.Gen
import is.hail.expr.ir.EmitMethodBuilder
import is.hail.expr.types.TSet
import org.json4s.jackson.JsonMethods

import scala.reflect.{ClassTag, _}

final case class PSet(elementType: PType, override val required: Boolean = false) extends PIterable {
  def virtualType: TSet = TSet(elementType.virtualType, required)

  val elementByteSize: Long = UnsafeUtils.arrayElementSize(elementType)

  val contentsAlignment: Long = elementType.alignment.max(4)

  override val fundamentalType: PArray = PArray(elementType.fundamentalType, required)

  def _toPretty = s"Set[$elementType]"

  override def pyString(sb: StringBuilder): Unit = {
    sb.append("set<")
    elementType.pyString(sb)
    sb.append('>')
  }

  override def canCompare(other: PType): Boolean = other match {
    case PSet(otherType, _) => elementType.canCompare(otherType)
    case _ => false
  }

  override def unify(concrete: PType): Boolean = concrete match {
    case PSet(celementType, _) => elementType.unify(celementType)
    case _ => false
  }

  override def subst() = PSet(elementType.subst())

  def _typeCheck(a: Any): Boolean =
    a.isInstanceOf[Set[_]] && a.asInstanceOf[Set[_]].forall(elementType.typeCheck)

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean = false) {
    sb.append("Set[")
    elementType.pretty(sb, indent, compact)
    sb.append("]")
  }

  val ordering: ExtendedOrdering =
    ExtendedOrdering.setOrdering(elementType.ordering)

  def codeOrdering(mb: EmitMethodBuilder, other: PType): CodeOrdering = {
    assert(other isOfType this)
    CodeOrdering.setOrdering(virtualType, other.asInstanceOf[PSet].virtualType, mb)
  }

  override def genNonmissingValue: Gen[Annotation] = Gen.buildableOf[Set](elementType.genValue)

  override def scalaClassTag: ClassTag[Set[AnyRef]] = classTag[Set[AnyRef]]
}
