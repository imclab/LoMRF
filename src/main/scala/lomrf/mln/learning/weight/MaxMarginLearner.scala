/*
 *
 *  o                        o     o   o         o
 *  |             o          |     |\ /|         | /
 *  |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
 *  |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
 *  O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
 *              |
 *           o--o
 *  o--o              o               o--o       o    o
 *  |   |             |               |    o     |    |
 *  O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
 *  |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
 *  o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o
 *
 *  Logical Markov Random Fields (LoMRF).
 *
 *
 */

package lomrf.mln.learning.weight

import java.text.DecimalFormat
import lomrf.mln.learning.weight.LossFunction.LossFunction
import lomrf.mln.inference.ILP
import lomrf.mln.model.{ AtomEvidenceDB, PredicateSpace }
import lomrf.util.time._
import java.io.PrintStream
import com.typesafe.scalalogging.LazyLogging
import gnu.trove.map.hash.TIntObjectHashMap
import lomrf.logic.{ FALSE, TRUE, TriState }
import lomrf.logic.AtomSignature
import lomrf.mln.grounding.DependencyMap
import lomrf.mln.model.mrf._
import optimus.optimization._
import optimus.algebra._
import optimus.algebra.AlgebraOps._
import optimus.optimization.enums.{ PreSolve, SolverLib }
import optimus.optimization.model.MPFloatVar
import spire.syntax.cfor._
import scala.language.postfixOps

/**
  * This is an implementation of max-margin weight learning algorithm for parameter estimation in Markov Logic Networks.
  * The original implementation of the algorithm can be found in: [[http://http://alchemy.cs.washington.edu]].
  * Details about the max-margin algorithm for MLNs can be found in the following publications:
  *
  * <ul>
  * <li> Tuyen N. Huynh and Raymond J. Mooney. Max-Margin Weight Learning for Markov Logic Networks (2009)
  * In Proceedings of the European Conference on Machine Learning and Principles and Practice of Knowledge Discovery
  * in Databases, Part 1, pp. 564--579, Bled, Slovenia, September 2009.
  * The paper can be found in [[http://www.cs.utexas.edu/users/ai-lab/?huynh:srl09]]
  * </li>
  * </ul>
  *
  * @param mrf The ground Markov network
  * @param annotationDB Annotation database holding the ground truth values for non evidence
  *                     atoms. Required when calculating loss.
  * @param nonEvidenceAtoms Non evidence atoms i.e. atoms not having any kind of evidence
  * @param iterations Maximum numbers of iterations for weight learning
  * @param C Regularization parameter for soft-margin
  * @param epsilon Stopping criterion parameter
  * @param lossFunction Type of loss function
  * @param lossScale Loss scaling parameter
  * @param nonMarginRescaling Disable margin rescaling
  * @param lossAugmented Use loss augmented inference
  * @param ilpSolver Solver type selection option for ILP inference (default is LPSolve)
  * @param L1Regularization Perform loss augmented inference using hamming distance (default is false)
  * @param printLearnedWeightsPerIteration Print learned weights for each iteration
  */
final class MaxMarginLearner(mrf: MRF, annotationDB: Map[AtomSignature, AtomEvidenceDB],
    nonEvidenceAtoms: Set[AtomSignature], iterations: Int = 1000, C: Double = 1e+3, epsilon: Double = 0.001,
    lossFunction: LossFunction = LossFunction.HAMMING, lossScale: Double = 1.0, nonMarginRescaling: Boolean = false,
    lossAugmented: Boolean = false, ilpSolver: SolverLib = SolverLib.oJSolver, L1Regularization: Boolean = false,
    printLearnedWeightsPerIteration: Boolean = false) extends LazyLogging {

  private val _ilpSolver = ilpSolver match {
    case SolverLib.LpSolve =>
      logger.warn {
        s"""
           |MAX_MARGIN training is supported only by GUROBI, MOSEK and OJALGO solvers.
           |Switching to OJALGO. In case you have a license, use GUROBI or MOSEK for better performance.
         """.stripMargin
      }

      SolverLib.oJSolver
    case _ => ilpSolver
  }

  // Select the appropriate mathematical programming solver
  implicit val model: MPModel = MPModel(_ilpSolver)

  // Number of first-order CNF clauses
  val numberOfClauses: Int = mrf.mln.clauses.length

  /* The number of examples is the number of ground atoms, because the non evidence (annotated data)
   * are always the ones which are used as query atoms in order to build the ground network.
   */
  val numberOfExamples: Int = mrf.atoms.size()

  // Initialise weights of the first-order clauses to zero (useless)
  val weights: Array[Double] = Array.fill[Double](numberOfClauses)(0.0)

  // Create an mrf state
  val state = MRFState(mrf)

  // Get the dependency map for the ground network if any exist
  val dependencyMap: DependencyMap = mrf.dependencyMap.getOrElse(sys.error("Dependency map does not exists."))

  val space: PredicateSpace = mrf.mln.space

  /**
    * Fetch atom given its literal code.
    *
    * @param literal Code of the literal
    * @return The ground atom which corresponds to the given literal code
    */
  @inline private def fetchAtom(literal: Int) = mrf.atoms.get(math.abs(literal))

  /**
    * Fetch annotation from database for the given atom id. Annotation
    * exist only for non evidence atoms.
    *
    * @param atomID id of the atom
    * @return annotation TriState value (TRUE, FALSE or UNKNOWN)
    */
  @inline private def getAnnotation(atomID: Int): TriState = {
    val annotation = annotationDB(space.signatureOf(atomID))
    annotation(atomID)
  }

  /**
    * Count the number of true groundings of each clause in the data.
    * In order to do this, we compute the satisfied literals of each
    * ground clause given an MRF state (annotation or inferred). Then
    * if the number of satisfied literals are greater than zero and
    * the weight of the clause that produced it has not been flipped
    * we can count it as true ground clause. On the other hand if the
    * weight has been flipped then in order to count it the number of
    * satisfied literals should be zero.
    *
    * @return count of true groundings of the clauses
    */
  @inline private def countGroundings(): Array[Int] = {

    val counts = Array.fill[Int](numberOfClauses)(0)

    val constraintIterator = mrf.constraints.iterator()

    // Keeps the count of literals satisfying the current constraint
    var nsat = 0

    // literal index of the current constraint
    var idx = 0

    while (constraintIterator.hasNext) {
      constraintIterator.advance()
      val currentConstraint = constraintIterator.value()

      // --- Compute the number of literals that satisfy the current constraint
      nsat = 0 // Reset
      idx = 0 // Reset
      while (idx < currentConstraint.literals.length) {
        val lit = currentConstraint.literals(idx)
        if ((lit > 0) == fetchAtom(lit).state) nsat += 1
        idx += 1
      }

      val iterator = dependencyMap.get(currentConstraint.id).iterator()

      while (iterator.hasNext) {
        iterator.advance()
        val clauseIdx = iterator.key()
        val frequency = iterator.value()

        // If weight is flipped then we want to count the opposite type of grounding
        // Use math abs because frequency may be negative to indicate a weight is flipped
        if ((frequency < 0 && nsat == 0) || (frequency > 0 && nsat > 0))
          counts(clauseIdx) += math.abs(frequency.toInt) // Clauses cannot have float frequencies at this point!
      }

      // --- --- --- --- --- --- --- --- --- ---
    }
    counts
  }

  /**
    * Set the annotated state as current MRF state.
    */
  @inline private def setAnnotatedState(): Unit = {

    val atomsIterator = mrf.atoms.iterator()

    while (atomsIterator.hasNext) {
      atomsIterator.advance()
      val atom = atomsIterator.value()
      if (getAnnotation(atom.id) == TRUE) atom.state = true
      else atom.state = false
    }
  }

  /**
    * Calculates the total error of inferred atom truth values. The
    * result is not divided by the number of examples.
    *
    * Currently working only for Hamming loss
    *
    * @return the total error
    */
  @inline private def calculateError(): Double = {
    var totalError = 0.0

    logger.info("Calculating misclassifed loss...")

    val iterator = mrf.atoms.iterator()
    while (iterator.hasNext) {
      iterator.advance()
      val atom = iterator.value()
      val annotation = getAnnotation(atom.id)
      if ((atom.state && annotation == FALSE) || (!atom.state && annotation == TRUE))
        totalError += 1.0
    }

    logger.info("Total inferred error: " + totalError + "/" + numberOfExamples)
    totalError
  }

  /**
    * Update constraint weights from the sum of newly found parent
    * weights in order to reconstruct the ground network faster in
    * order to run inference.
    *
    * Basic reconstruction steps:
    *
    * For each clause that produced the constaint do:
    *    If weight has been inverted then:
    *      multiply the clause weight learned so far and the number of times (frequency)
    *      this clause produced the corresponding constraint and subtract the result from
    *      the total weight of the constraint so far.
    *
    *    If weight has not been inverted then:
    *      Do the same but add the result to the total weight instead of subtract it.
    *
    * Note: In case the clause is hard then just assign the hard weight to the constraint.
    */
  @inline private def updateConstraintWeights(): Unit = {

    val constraints = mrf.constraints.iterator()

    while (constraints.hasNext) {
      constraints.advance()
      val constraint = constraints.value()
      val iterator = dependencyMap.get(constraint.id).iterator()

      constraint.setWeight(0.0)
      while (iterator.hasNext) {
        iterator.advance()

        val clauseIdx = iterator.key()
        val frequency = iterator.value()

        // Frequency would never be negative because we always start using positive unit weights
        if (mrf.mln.clauses(clauseIdx).isHard) constraint.setWeight(mrf.weightHard)
        else constraint.setWeight(constraint.getWeight + weights(clauseIdx) * frequency)
      }
    }
  }

  /**
    * Reconstruct the ground network without running the grounding procedure
    * and then perform inference using the ILP solver.
    */
  @inline private def infer() = {
    updateConstraintWeights()
    val solver = if (lossAugmented) ILP(mrf, _ilpSolver, annotationDB = Some(annotationDB)) else ILP(mrf, _ilpSolver)
    solver.infer
  }

  // Set the annotation as current state and count true groundings
  setAnnotatedState()
  val trueCounts: Array[Int] = countGroundings()

  logger.info("True Counts: [" + trueCounts.deep.mkString(", ") + "]")

  def learn(): Unit = {

    if (L1Regularization)
      logger.info("1-norm max margin weight learning using cutting plane method: \n" +
        "Number of weights: " + numberOfClauses)
    else
      logger.info("2-norm max margin weight learning using cutting plane method: \n" +
        "Number of weights: " + numberOfClauses)

    val sLearning = System.currentTimeMillis()

    var error = 1e+5
    var slack = -1e+5
    var iteration = 1

    val LPVars = new TIntObjectHashMap[MPFloatVar]()
    var expressions = List[Expression]()
    var constraints = List[Expression]()

    if (L1Regularization) {
      /*
       * Preprocessing step: Define the objective function before learning process begins.
       *
       * w + slack
       *
       * where both w and slack variables are vectors.
       *
       * Step 1: Introduce two variables for each first-order clause weight and one slack variable
       * Step 2: Create sub-expressions for objective function (quadratic problem)
       *
       * Note: Weights are positive only!
       */

      cfor(0) (_ < 2 * numberOfClauses, _ + 1) { clauseIdx: Int =>
        LPVars.putIfAbsent(clauseIdx, MPFloatVar("w" + clauseIdx, 0)) // bounds are [0.0, inf] because L1 norm has absolute values
        expressions :+= LPVars.get(clauseIdx)
      }

      LPVars.putIfAbsent(2 * numberOfClauses, MPFloatVar("slack", 0)) // bounds are [0.0, inf]
      expressions ::= (C * LPVars.get(numberOfClauses))
    } else {
      /*
       * Preprocessing step: Define the objective function before learning process begins.
       *
       * 1/2 ||w||^2 + slack
       *
       * where both w and slack variables are vectors.
       *
       * Step 1: Introduce variables for each first-order clause weight and one slack variable
       * Step 2: Create sub-expressions for objective function (quadratic problem)
       */

      cfor(0)(_ < numberOfClauses, _ + 1) { clauseIdx: Int =>
        LPVars.putIfAbsent(clauseIdx, MPFloatVar("w" + clauseIdx))
        expressions :+= (0.5 * LPVars.get(clauseIdx) * LPVars.get(clauseIdx))
      }

      LPVars.putIfAbsent(numberOfClauses, MPFloatVar("slack", 0)) // bounds are [0.0, inf]
      expressions ::= (C * LPVars.get(numberOfClauses))
    }

    logger.debug("Expressions: [" + expressions.mkString(", ") + "]")

    // Step 3: Sum the sub-expressions to create the final objective
    minimize(sum(expressions))

    while (error > (slack + epsilon) && iteration <= iterations) {

      logger.info("Iteration: " + iteration + "/" + iterations)

      // We should try and learn the parameters on the 2nd iteration, first we must perform inference
      if (iteration > 1) {

        logger.info("Running solver for the current QP problem...")

        // Optimize function subject to the constraints introduced
        val s = System.currentTimeMillis()
        start(PreSolve.CONSERVATIVE)
        logger.info(msecTimeToText("Optimization time: ", System.currentTimeMillis() - s))

        logger.info {
          s"""
             |
             |=========================== Solution ===========================
             |Are constraints satisfied: ${checkConstraints()}
             |Solution status: ${status.toString}
             |Objective = $objectiveValue
           """.stripMargin
        }

        // Check for convergence and terminate learning if required
        var converged = true
        var nonZero = 0

        cfor(0)(_ < numberOfClauses, _ + 1) { clauseIdx: Int =>
          val value =
            if (L1Regularization) LPVars.get(clauseIdx).value.get - LPVars.get(clauseIdx + numberOfClauses).value.get
            else LPVars.get(clauseIdx).value.get

          // set learned weights before inference if they have been changed
          if (weights(clauseIdx) != value) {
            weights(clauseIdx) = value
            converged = false
          }
        }

        // Count the number of weights that are non zero.
        nonZero = weights.count(w => w != 0.0)
        logger.info("Non-zero weights: " + nonZero)

        val value =
          if (L1Regularization) LPVars.get(2 * numberOfClauses).value.get
          else LPVars.get(numberOfClauses).value.get
        if (slack != value) slack = value

        logger.info("Current slack value: " + slack)

        // Print learned weights so far
        if (printLearnedWeightsPerIteration) {
          logger.info(s"Learned weights on iteration $iteration:\n ${weights.deep.mkString("[", ", ", "]")}")
        }

        if (converged) iteration = iterations + 1
      }

      // Run inference for learned weights of this iteration
      infer()

      val loss = calculateError() * lossScale
      logger.info("Current loss: " + loss)

      val inferredCounts = countGroundings()
      logger.info("Inferred Counts: " + inferredCounts.deep.mkString("[", ", ", "]"))

      // Calculate true counts minus inferred counts
      var currentError = 0.0
      val delta =
        if (L1Regularization) Array.fill[Int](2 * numberOfClauses)(0)
        else Array.fill[Int](numberOfClauses)(0)

      cfor(0)(_ < numberOfClauses, _ + 1) { clauseIdx: Int =>
        if (!mrf.mln.clauses(clauseIdx).isHard) {
          delta(clauseIdx) = trueCounts(clauseIdx) - inferredCounts(clauseIdx)
          if (L1Regularization) delta(clauseIdx + numberOfClauses) = -delta(clauseIdx)
        }
        currentError += weights(clauseIdx) * delta(clauseIdx)
      }

      logger.info {
        s"""
           |Delta = ${delta.deep.mkString("[", ", ", "]")}
           |Count difference: ${delta.sum}
           |Current weighted count difference: $currentError
         """.stripMargin
      }

      // Add next constraint to the quadratic solver in order to refine weights
      constraints = Nil
      if (L1Regularization) {
        cfor(0) (_ < 2 * numberOfClauses, _ + 1){ variableIdx: Int =>
          constraints ::= LPVars.get(variableIdx) * delta(variableIdx)
        }
      } else {
        cfor(0) (_ < numberOfClauses, _ + 1) { clauseIdx: Int =>
          constraints ::= LPVars.get(clauseIdx) * delta(clauseIdx)
        }
      }

      // Do not scale the margin by the loss if required
      if (nonMarginRescaling) add(sum(constraints) >:= 1 - LPVars.get(numberOfClauses))
      else add(sum(constraints) >:= loss - LPVars.get(numberOfClauses))

      logger.debug(sum(constraints) + " >= " + (loss - LPVars.get(numberOfClauses)))

      // update error for termination condition
      if (nonMarginRescaling) {
        if (loss > 0) error = 1 - currentError
        else error = 0.0
      } else
        error = loss - currentError

      logger.info(s"Current error: $error\nCurrent stopping criteria: ${(slack + epsilon)}")

      iteration += 1
    }

    // Release quadratic solver
    release()

    val eLearning = System.currentTimeMillis()
    logger.info(msecTimeToText("Total weight learning time: ", eLearning - sLearning))
  }

  /**
    * Write clauses and their corresponding learned weights in the output
    * mln file together with the predicate and function schema if any exists.
    *
    * @param out Selected output stream (default is console)
    */
  def writeResults(out: PrintStream = System.out): Unit = {

    val numFormat = new DecimalFormat("0.############")

    out.println("// Predicate definitions")
    for ((signature, args) <- mrf.mln.schema.predicates) {
      val line = signature.symbol + (
        if (args.isEmpty) "\n"
        else "(" + args.mkString(",") + ")\n")
      out.print(line)
    }

    if (mrf.mln.schema.functions.nonEmpty) {
      out.println("\n// Functions definitions")
      for ((signature, (retType, args)) <- mrf.mln.schema.functions) {
        val line = retType + " " + signature.symbol + "(" + args.mkString(",") + ")\n"
        out.print(line)
      }
    }

    out.println("\n// Clauses")
    for ((clause, clauseIdx) <- mrf.mln.clauses.zipWithIndex) {
      if (clause.isHard) out.println(clause.toText(weighted = false) + ".\n")
      else out.println(numFormat.format(weights(clauseIdx)) + " " + clause.toText(weighted = false) + "\n")
    }
  }

}

/**
  * Object holding constants for loss function type.
  */
object LossFunction extends Enumeration {
  type LossFunction = Value
  val HAMMING = Value
  val F1Score = Value
}
