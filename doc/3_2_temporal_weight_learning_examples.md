# Temporal Weight Learning Examples

Below we provide examples that demonstrate LoMRF weight learning capabilities in the domain of temporal reasoning.

## Activity Recognition

In this example we demonstrate how to perform weight learning for activity recognition, using a small fragment of the first set of the [CAVIAR dataset](http://homepages.inf.ed.ac.uk/rbf/CAVIARDATA1/). We use the same Probabilistic Event Calculus formalism as presented in the Section [Quick Start](0_quick_start.md)   (for further details see [Skarlatidis et. al. (2014, 2015)](#referencies)) and the same knowledge base as the one defined in the [Temporal Inference Examples](2_2_temporal_inference_examples.md).

### Training data

The training data is composed of ground facts of the input predicates `StartTime/1`, `Happens/2`, `Close/4`, `OrientationMove/3`,
ground facts of the annotation predicates `HoldsAt/2`, as well as ground function mappings. For example, consider the following fragment:

```lang-none
// Input events:
Enter_ID0 = enter(ID0)
Enter_ID1 = enter(ID1)
Exit_ID0 = exit(ID0)
Exit_ID1 = exit(ID1)
Walking_ID0 = walking(ID0)
Walking_ID1 = walking(ID1)
Running_ID0 = running(ID0)
Running_ID1 = running(ID1)
Active_ID0 = active(ID0)
Active_ID1 = active(ID1)
Inactive_ID0 = inactive(ID0)
Inactive_ID1 = inactive(ID1)

// Output composite events (fluents):
Move_ID0_ID0 = move(ID0, ID0)
Move_ID0_ID1 = move(ID0, ID1)
Move_ID1_ID0 = move(ID1, ID0)
Move_ID1_ID1 = move(ID1, ID1)

Meet_ID0_ID0 = meet(ID0, ID0)
Meet_ID0_ID1 = meet(ID0, ID1)
Meet_ID1_ID0 = meet(ID1, ID0)
Meet_ID1_ID1 = meet(ID1, ID1)

// Facts
StartTime(0)

HoldsAt(Meet_ID0_ID1, 174)
HoldsAt(Meet_ID0_ID1, 175)
HoldsAt(Meet_ID0_ID1, 176)
//
// ... sequence of facts ...
//
Happens(Walking_ID0, 100)
Happens(Walking_ID1, 100)
OrientationMove(ID0, ID1, 100)
Close(ID0, ID1, 34, 100)
//
// ... sequence of facts ...
//
Happens(Active_ID0, 170)
Happens(Active_ID1, 170)
//
// ... sequence of facts ...
```

### Weight Learning

The files of this example are the following:
  * Knowledge base files:
    * Main MLN file in CNF: [theory_cnf.mln](https://github.com/anskarl/LoMRF-data/tree/master/Examples/Weight_Learning/Activity_Recognition/theory.mln)
    * Definitions of moving activity: [definitions/moving.mln](https://github.com/anskarl/LoMRF-data/tree/master/Examples/Weight_Learning/Activity_Recognition/definitions/moving.mln)
    * Definitions of meeting activity: [definitions/meeting.mln](https://github.com/anskarl/LoMRF-data/tree/master/Examples/Weight_Learning/Activity_Recognition/definitions/meeting.mln)
  * Training file for batch learning: [training.db](https://github.com/anskarl/LoMRF-data/tree/master/Examples/Weight_Learning/Activity_Recognition/training/batch/training.db)
  * Training files for online learning: [micro-batches](https://github.com/anskarl/LoMRF-data/tree/master/Examples/Weight_Learning/Activity_Recognition/training/online/)


Parameters:
 * Non-evidence predicates: `-ne HoldsAt/2`
 * Input MLN theory: `-i theory_cnf.mln`
 * Input training data: `-t training.db`
 * Resulting output MLN theory: `-o learned.mln`
 * Enable loss augmented inference (also known as seperation oracle) using the Hamming loss function by adding to the objective function during inference additional loss terms: `-lossAugmented`
 * Specify the learning alogirhtm, i.e., Max-Margin (default), Adagrad or CDA: `-alg`

***Max-Margin Learning***

```lang-none
lomrf-wlearn -alg MAX_MARGIN -i theory_cnf.mln -t training.db -o learned.mln -ne HoldsAt/2 -lossAugmented
```
***Online Learning using AdaGrad or CDA***

```lang-none
lomrf-wlearn -alg ADAGRAD -i theory_cnf.mln -t ./training/online -o learned.mln -ne HoldsAt/2

lomrf-wlearn -alg CDA -i theory_cnf.mln -t ./training/online -o learned.mln -ne HoldsAt/2 -lossAugmented
```

## References
* Skarlatidis A. Event Recognition Under Uncertainty and Incomplete Data. (2014). PhD Thesis. Department of Digital Systems, University of Piraeus. ([link](http://hdl.handle.net/10442/hedi/35692))

* Skarlatidis A., Paliouras G., Artikis A. and Vouros G. (2015). Probabilistic Event Calculus for Event Recognition. ACM Transactions on Computational Logic, 16, 2, Article 11, pp. 11:1-11:37. ([link](http://dx.doi.org/10.1145/2699916))