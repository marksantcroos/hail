package is.hail.expr

import is.hail.asm4s._
import is.hail.expr

package object ir {
  def coerce[T](x: Code[_]): Code[T] = x.asInstanceOf[Code[T]]

  def typeToTypeInfo(t: expr.Type): TypeInfo[_] = t match {
    case _: TBoolean => typeInfo[Boolean]
    case _: TInt32 => typeInfo[Int]
    case _: TInt64 => typeInfo[Long]
    case _: TFloat32 => typeInfo[Float]
    case _: TFloat64 => typeInfo[Double]
    case _ => typeInfo[Long] // reference types
  }

  // FIXME add InsertStruct IR node
  def insertStruct(s: ir.IR, typ: TStruct, name: String, v: ir.IR): ir.IR = {
    assert(typ.hasField(name))
    ir.MakeStruct(typ.fields.zipWithIndex.map { case (f, i) =>
      (f.name,
        if (f.name == name)
          v
        else
          ir.GetField(s, f.name))
    }.toArray)
  }
}
