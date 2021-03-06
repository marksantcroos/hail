package is.hail.expr.ir

import is.hail.expr.types.virtual.Type
import is.hail.utils.ArrayBuilder

object FreeVariables {
  def apply(ir: IR): Array[Ref] = {

    val freeVars = new ArrayBuilder[Ref]()

    def compute(ir1: IR, env: BindingEnv[Type]): Unit = {
      ir1 match {
        case x@Ref(name, _) =>
          if (env.eval.lookupOption(name).isEmpty)
            freeVars += x
        case TableAggregate(_, _) =>
        case MatrixAggregate(_, _) =>
        case _ =>
          ir1.children
            .iterator
            .zipWithIndex
            .foreach {
              case (child: IR, i) =>
                val base = ChildEnvWithoutBindings(ir1, i, env)
                compute(child, base.merge(NewBindings(ir1, i, base)))
              case _ =>
            }
      }
    }

    compute(ir, BindingEnv(Env.empty, Some(Env.empty), Some(Env.empty)))

    freeVars.result()
  }
}
