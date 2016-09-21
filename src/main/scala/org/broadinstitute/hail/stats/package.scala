package org.broadinstitute.hail

import org.apache.commons.math3.distribution.HypergeometricDistribution
import org.broadinstitute.hail.annotations.Annotation
import org.broadinstitute.hail.expr.{TDouble, TInt, TStruct}
import org.broadinstitute.hail.utils._
import org.broadinstitute.hail.variant.Genotype

package object stats {

  object InfoScoreCombiner {
    def signature = TStruct("score" -> TDouble, "nIncluded" -> TInt)
  }

  class InfoScoreCombiner {
    var result = 0d
    var expectedAlleleCount = 0d
    var totalDosage = 0d
    var nIncluded = 0

    def expectedVariance(dosage: Array[Double], mean: Double): Double = (dosage(1) + 4 * dosage(2)) - (mean * mean)

    def merge(g: Genotype): InfoScoreCombiner = {
      g.dosage.foreach { dx =>
        val mean = dx(1) + 2 * dx(2)
        result += expectedVariance(dx, mean)
        expectedAlleleCount += mean
        totalDosage += dx.sum
        nIncluded += 1
      }
      this
    }

    def merge(that: InfoScoreCombiner): InfoScoreCombiner = {
      result += that.result
      expectedAlleleCount += that.expectedAlleleCount
      totalDosage += that.totalDosage
      nIncluded += that.nIncluded
      this
    }

    def thetaMLE = divOption(expectedAlleleCount, totalDosage)

    def imputeInfoScore(theta: Double) =
      if (theta == 1.0 || theta == 0.0)
        Some(1d)
      else if (nIncluded == 0)
        None
      else
        Some(1d - ((result / nIncluded) / (2 * theta * (1 - theta))))

    def asAnnotation: Annotation = {
      val score = thetaMLE.flatMap { theta => imputeInfoScore(theta) }

      Annotation(score.orNull, nIncluded)
    }
  }

  def uniroot(fn: Double => Double, min: Double, max: Double, tolerance: Double = 1.220703e-4): Option[Double] = {
    // based on C code in R source code called zeroin.c
    // https://github.com/wch/r-source/blob/e5b21d0397c607883ff25cca379687b86933d730/src/library/stats/src/zeroin.c

    require(min < max, "interval start must be larger than end")

    var a = min
    var b = max
    var c = min

    var fa: Double = fn(a)
    var fb: Double = fn(b)
    var fc: Double = fa

    val epsilon = 2.2204460492503131e-16

    val maxIterations = 1001
    var numIterations = 0

    if (fa == 0d)
      Option(a)
    else if (fb == 0d)
      Option(b)
    else {
      while (numIterations != maxIterations) {
        numIterations += 1
        val previousStep = b - a
        var q = 0d
        var p = 0d
        val toleranceActual = 2 * epsilon * math.abs(b) + tolerance / 2
        var newStep = (c - b) / 2

        if (math.abs(fc) < math.abs(fb)) {
          // swap endpoints so b is best approx
          a = b;
          b = c;
          c = a
          fa = fb;
          fb = fc;
          fc = fa
        }

        if (math.abs(newStep) <= toleranceActual || fb == 0d) {
          return Option(b) // acceptable approximation is found
        }

        if (math.abs(previousStep) >= toleranceActual && math.abs(fa) > math.abs(fb)) {
          // try interpolation
          val cb = c - b

          if (a == c) {
            // if only two distinct points, linear interpolation can only be applied
            val t1 = fb / fa
            p = cb * t1
            q = 1.0 - t1
          } else {
            // quadric inverse interpolation
            q = fa / fc
            val t1 = fb / fc
            val t2 = fb / fa
            p = t2 * (cb * q * (q - t1) - (b - a) * (t1 - 1d))
            q = (q - 1d) * (t1 - 1d) * (t2 - 1d)
          }

          if (p > 0d) //p was calculated with opposite sign
            q = -q
          else
            p = -p

          if (p < (0.75 * cb * q - math.abs(toleranceActual * q) / 2) &&
            p < math.abs(previousStep * q / 2))
            newStep = p / q
        }

        if (math.abs(newStep) < toleranceActual) {
          if (newStep > 0d)
            newStep = toleranceActual
          else
            newStep = -1 * toleranceActual
        }

        a = b;
        fa = fb
        b += newStep
        fb = fn(b)

        if ((fb > 0d && fc > 0d) || (fb < 0d && fc < 0d)) {
          c = a
          fc = fa
        }
      }

      None // No value found within threshold with 1000 iterations
    }
  }

  def FisherExactTest(a: Int, b: Int, c: Int, d: Int,
    oddsRatio: Double = 1d, confidence_level: Double = 0.95,
    alternative: String = "two.sided"): Array[Option[Double]] = {

    if (!(a >= 0 && b >= 0 && c >= 0 && d >= 0))
      fatal(s"All inputs must be >= 0. Found [$a, $b, $c, $d]")

    if (confidence_level < 0d || confidence_level > 1d)
      fatal("Confidence level must be between 0 and 1")

    if (oddsRatio < 0d)
      fatal("Odds ratio must be between 0 and Inf")

    if (alternative != "greater" && alternative != "less" && alternative != "two.sided")
      fatal("Did not recognize test type string. Use one of greater, less, two.sided")

    val popSize = a + b + c + d
    val numSuccessPopulation = a + c
    val sampleSize = a + b
    val numSuccessSample = a

    if (!(popSize > 0 && sampleSize > 0 && sampleSize < popSize))
      return Array(None, None, None, None)

    val low = math.max(0, (a + b) - (b + d))
    val high = math.min(a + b, a + c)
    val support = (low to high).toArray

    val hgd = new HypergeometricDistribution(null, popSize, numSuccessPopulation, sampleSize)
    val epsilon = 2.220446e-16

    def dhyper(k: Int, logProb: Boolean = false): Double = {
      if (logProb) hgd.logProbability(k) else hgd.probability(k)
    }

    val logdc = support.map(dhyper(_, logProb = true))

    def dnhyper(ncp: Double): Array[Double] = {
      var d = logdc.zipWithIndex.map { case (hr, i) => hr + math.log(ncp) * i }
      d = d.map(dens => math.exp(dens - d.max))
      d.map(_ / d.sum)
    }

    def phyper(k: Int, lower_tail: Boolean = true): Double = {
      if (lower_tail)
        hgd.cumulativeProbability(k)
      else
        hgd.upperCumulativeProbability(k)
    }

    def pnhyper(q: Int, ncp: Double = 1d, upper_tail: Boolean = false): Double = {
      if (ncp == 1d) {
        if (upper_tail)
          phyper(q, lower_tail = false)
        else
          phyper(q, lower_tail = true)
      } else if (ncp == 0d) {
        if (upper_tail)
          if (q <= low) 1d else 0d
        else if (q >= low) 1d else 0d
      } else if (ncp == Double.PositiveInfinity) {
        if (upper_tail)
          if (q <= high) 1d else 0d
        else if (q >= high) 1d else 0d
      } else {
        dnhyper(ncp)
          .zipWithIndex
          .filter { case (dbl, i) => if (upper_tail) support(i) >= q else support(i) <= q }
          .map { case (dbl, i) => dbl }
          .sum
      }
    }

    def mnhyper(ncp: Double): Double = {
      if (ncp == 0d)
        low
      else if (ncp == Double.PositiveInfinity)
        high
      else
        dnhyper(ncp).zipWithIndex.map { case (dnh, i) => dnh * support(i) }.sum
    }

    def unirootMnHyper(fn: Double => Double, x: Double)(t: Double) = mnhyper(fn(t)) - x

    def unirootPnHyper(fn: Double => Double, x: Int, upper_tail: Boolean, alpha: Double)(t: Double) = pnhyper(x, fn(t), upper_tail) - alpha

    def inverse(x: Double) = divOption(1d, x)

    def mle(x: Double): Option[Double] = {
      if (x == low)
        Option(0d)
      else if (x == high)
        Option(Double.PositiveInfinity) // Should be infinity
      else {
        val mu = mnhyper(1.0)
        if (mu > x)
          uniroot(unirootMnHyper(d => d, x), 0.0, 1.0)
        else if (mu < x)
          uniroot(unirootMnHyper(d => 1 / d, x), epsilon, 1d).flatMap(inverse)
        else
          Option(1d)
      }
    }

    def ncpLower(x: Int, alpha: Double): Option[Double] = {
      if (x == low)
        Option(0d)
      else {
        val p = pnhyper(x, 1, upper_tail = true)
        if (p > alpha)
          uniroot(unirootPnHyper(d => d, x, upper_tail = true, alpha), 0d, 1d)
        else if (p < alpha)
          uniroot(unirootPnHyper(d => 1 / d, x, upper_tail = true, alpha), epsilon, 1d).flatMap(inverse)
        else
          Option(1d)
      }
    }

    def ncpUpper(x: Int, alpha: Double): Option[Double] = {
      if (x == high) {
        Option(Double.PositiveInfinity)
      }
      else {
        val p = pnhyper(x, 1)
        if (p < alpha)
          uniroot(unirootPnHyper(d => d, x, upper_tail = false, alpha), 0d, 1d)
        else if (p > alpha)
          uniroot(unirootPnHyper(d => 1 / d, x, upper_tail = false, alpha), epsilon, 1d).flatMap(inverse)
        else
          Option(1d)
      }
    }

    val pvalue = alternative match {
      case "less" => pnhyper(numSuccessSample, oddsRatio)
      case "greater" => pnhyper(numSuccessSample, oddsRatio, upper_tail = true)
      case "two.sided" =>
        if (oddsRatio == 0)
          if (low == numSuccessSample) 1d else 0d
        else if (oddsRatio == Double.PositiveInfinity)
          if (high == numSuccessSample) 1d else 0d
        else {
          val relErr = 1d + 1e-7
          val d = dnhyper(oddsRatio)
          d.filter(_ <= d(numSuccessSample - low) * relErr).sum
        }
      case _ => fatal("didn't recognize option for alternative. Use one of [less, greater, two.sided]")
    }

    assert(pvalue >= 0d && pvalue <= 1.000000000002)

    val oddsRatioEstimate = mle(numSuccessSample)

    val confInterval = alternative match {
      case "less" => (Option(0d), ncpUpper(numSuccessSample, 1 - confidence_level))
      case "greater" => (ncpLower(numSuccessSample, 1 - confidence_level), Option(Double.PositiveInfinity))
      case "two.sided" =>
        val alpha = (1 - confidence_level) / 2d
        (ncpLower(numSuccessSample, alpha), ncpUpper(numSuccessSample, alpha))
    }

    Array(Option(pvalue), oddsRatioEstimate, confInterval._1, confInterval._2)
  }
}
