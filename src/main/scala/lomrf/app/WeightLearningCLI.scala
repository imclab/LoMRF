/*
 * o                        o     o   o         o
 * |             o          |     |\ /|         | /
 * |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
 * |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
 * O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
 *             |
 *          o--o
 * o--o              o               o--o       o    o
 * |   |             |               |    o     |    |
 * O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
 * |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
 * o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o
 *
 * Logical Markov Random Fields.
 *
 * Copyright (C) 2012 Anastasios Skarlatidis.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package lomrf.app

import java.io.{FileOutputStream, PrintStream}
import gnu.trove.map.hash.TIntObjectHashMap
import auxlib.log.Logging
import auxlib.opt.OptionParser
import lomrf.logic.AtomSignature
import lomrf.mln.grounding.MRFBuilder
import lomrf.mln.inference.LossFunction
import lomrf.mln.model.MLN
import lomrf.mln.learning.weight.MaxMarginLearner
import lomrf.util._
import lomrf.util.parseAtomSignature

/**
 * Command-line tool for weight learning
 *
 * @author Anastasios Skarlatidis
 * @author Vagelis Michelioudakis
 */
object WeightLearningCLI extends OptionParser with Logging {

  // The path to the input MLN file
  private var _mlnFileName: Option[String] = None

  // The path to the output MLN file
  private var _outputFileName: Option[String] = None

  // Input training file(s) (path)
  private var _trainingFileName: Option[List[String]] = None

  // The set of non evidence atoms (in the form of AtomName/Arity)
  private var _nonEvidenceAtoms = Set[AtomSignature]()

  // Add unit clauses to the MLN output file
  private var _noAddUnitClauses = false

  // Regularization parameter
  private var _C = 1e+3

  // Stopping parameter
  private var _epsilon = 0.001

  // Loss function
  private val _lossFunction = LossFunction.HAMMING

  // The loss value will be multiplied by this number
  private var _lossScale = 1.0

  // Perform loss augmented inference
  private var _lossAugmented = true

  // Don't scale the margin by the loss
  private var _nonMarginRescaling = false

  // Number of iterations to run learning method
  private var _iterations = 1000

  // Use L1 regularization instead of L2
  private var _L1Regularization = false

  // Print the learned weights for each iteration
  private var _printLearnedWeightsPerIteration = false

  // Eliminate negative weights, i.e. convert the clause:
  // -2 A(x) v B(x)
  // into the following two clauses:
  // 1 !A(x)
  // 1 !B(x)
  private var _noNeg = false

  // Eliminate negated unit clauses
  // For example:
  // 2 !A(x) becomes -2 A(x)
  private var _eliminateNegatedUnit = false

  private var _implPaths: Option[Array[String]] = None


  private def addNonEvidenceAtom(atom: String) {
    parseAtomSignature(atom) match {
      case Some(s) => _nonEvidenceAtoms += s
      case None => fatal("Cannot parse the arity of query atom: " + atom)
    }
  }

  opt("i", "input", "<kb file>", "Markov Logic file", {
    v: String => _mlnFileName = Some(v)
  })

  opt("o", "output", "<output file>", "Output MLN file", {
    v: String => _outputFileName = Some(v)
  })

  opt("t", "training", "<training file>", "Training database file", {
    v: String => _trainingFileName = Some(v.split(',').toList)
  })

  opt("ne", "non-evidence atoms", "<string>", "Comma separated non-evidence atoms. "
    + "Each atom must be defined using its identity (i.e. Name/arity). "
    + "For example the identity of NonEvidenceAtom(arg1,arg2) is NonEvidenceAtom/2", _.split(',').foreach(v => addNonEvidenceAtom(v)))

  booleanOpt("noAddUnitClauses", "no-add-unit-clauses", "If specified, unit clauses are not included in the output MLN file" +
    " (default is " + _noAddUnitClauses + ").", _noAddUnitClauses = _)

  doubleOpt("C", "C", "Regularization parameter (default is " + _C + ").", {
    v: Double => _C = v
  })

  doubleOpt("epsilon", "epsilon", "Stopping parameter (default is " + _epsilon + ").", {
    v: Double => _epsilon = v
  })

  doubleOpt("lossScale", "loss-scale", "The loss value will be multiplied by this number (default is " + _lossScale + ").", {
    v: Double => _lossScale = v
  })

  intOpt("iterations", "maximum-iterations", "The maximum number of iterations to run learning (default is " + _iterations + ").", {
    v: Int => if (v < 0) fatal("The maximum iterations value must be any integer above zero, but you gave: " + v) else _iterations = v
  })

  opt("dynamic", "dynamic-implementations", "<string>", "Comma separated paths to search recursively for dynamic predicates/functions implementations (*.class and *.jar files).", {
    path: String => if (!path.isEmpty) _implPaths = Some(path.split(','))
  })

  flagOpt("L1Regularization", "L1-regularization", "Use L1 regularization instead of L2.", {_L1Regularization = true})

  flagOpt("printLearnedWeightsPerIteration", "print-learned-weights-per-iteration", "Print the learned weights for each iteration.", { _printLearnedWeightsPerIteration = true})

  flagOpt("lossAugmented", "loss-augmented", "Perform loss augmented inference.", {_lossAugmented = true})

  flagOpt("nonMarginRescaling", "non-margin-rescaling", "Don't scale the margin by the loss.", {_nonMarginRescaling = true})

  flagOpt("noNegWeights", "eliminate-negative-weights", "Eliminate negative weight values from ground clauses.", {_noNeg = true})

  flagOpt("noNegatedUnit", "eliminate-negated-unit", "Eliminate negated unit ground clauses.", {_eliminateNegatedUnit = true})

  flagOpt("v", "version", "Print LoMRF version.", sys.exit(0))

  flagOpt("h", "help", "Print usage options.", {
    println(usage)
    sys.exit(0)
  })

  def main(args: Array[String]) {

    println(lomrf.ASCIILogo)
    println(lomrf.BuildVersion)

    if (args.length == 0) println(usage)
    else if (parse(args)) weightLearn()
  }

  def weightLearn() = {

    val strMLNFileName = _mlnFileName.getOrElse(fatal("Please specify an input MLN file."))
    val strTrainingFileNames = _trainingFileName.getOrElse(fatal("Please specify input training file(s)."))

    val outputWriter = _outputFileName match {
      case Some(fileName) => new PrintStream(new FileOutputStream(fileName), true)
      case None => System.out
    }

    info("Parameters:"
      + "\n\t(ne) Non-evidence predicate(s): " + _nonEvidenceAtoms.map(_.toString).reduceLeft((left, right) => left + "," + right)
      + "\n\t(noAddUnitClauses) Include unit clauses in the output MLN file: " + _noAddUnitClauses
      + "\n\t(C) Regularization parameter: " + _C
      + "\n\t(epsilon) Stopping parameter: " + _epsilon
      + "\n\t(lossScale) Scale the loss value by: " + _lossScale
      + "\n\t(iterations) Number of iterations for learning: " + _iterations
      + "\n\t(L1Regularization) Use L1 regularization instead of L2: " + _L1Regularization
      + "\n\t(printLearnedWeightsPerIteration) Print learned weights for each iteration: " + _printLearnedWeightsPerIteration
      + "\n\t(lossAugmented) Perform loss augmented inference: " + _lossAugmented
      + "\n\t(nonMarginRescaling) Don't scale the margin by the loss: " + _nonMarginRescaling
      + "\n\t(noNeg) Eliminate negative weights: " + _noNeg
      + "\n\t(noNegatedUnit) Eliminate negated ground unit clauses: " + _eliminateNegatedUnit
    )

    val (mln, annotationDB) = MLN.learning(strMLNFileName, strTrainingFileNames, _nonEvidenceAtoms)

    info("Markov Logic:"
      + "\n\tConstant domains   : " + mln.constants.size
      + "\n\tSchema definitions : " + mln.predicateSchema.size
      + "\n\tFormulas           : " + mln.formulas.size
      + "\n\tEvidence atoms     : " + mln.cwa.map(_.toString).reduceLeft((left, right) => left + "," + right)
      + "\n\tNon-evidence atoms : " + mln.owa.map(_.toString).reduceLeft((left, right) => left + "," + right))

    info("AnnotationDB: "
      +"\n\tAtoms with annotations: " + annotationDB.keys.map(_.toString).reduceLeft((left, right) => left + "," + right)
    )

    info("Number of CNF clauses = " + mln.clauses.size)
    info("List of CNF clauses: ")
    mln.clauses.zipWithIndex.foreach{case (c, idx) => info(idx+": "+c)}

    info("Creating MRF...")
    val mrfBuilder = new MRFBuilder(mln, noNegWeights = _noNeg, eliminateNegatedUnit = _eliminateNegatedUnit, createDependencyMap = true)
    val mrf = mrfBuilder.buildNetwork

    info("--------------------------------------------------------------------------------------------------------------")
    info("GC: " + mrf.dependencyMap.get.size())
    val newMap = mrf.dependencyMap.get.iterator()
    while(newMap.hasNext) {
      newMap.advance()
      println(mrf.constraints.get(newMap.key()).literals.map {lit =>
        decodeLiteral(lit)(mln).get
      }.reduceLeft(_+" v "+_) + " :")
      val dependency = newMap.value().iterator()
      while(dependency.hasNext) {
        dependency.advance()
        if(mln.clauses(dependency.key()).isHard) println("XAXAXAXAXA")
        println(mln.clauses(dependency.key()) + " " + dependency.value())
      }
    }

    val learner = new MaxMarginLearner(mrf = mrf, annotationDB = annotationDB, nonEvidenceAtoms = _nonEvidenceAtoms,
                                        iterations = _iterations, C = _C, epsilon = _epsilon,
                                        lossScale = _lossScale, nonMarginRescaling = _nonMarginRescaling, lossAugmented = _lossAugmented,
                                        printLearnedWeightsPerIteration = _printLearnedWeightsPerIteration)

    learner.learn()
    learner.writeResults(outputWriter)
  }
}


