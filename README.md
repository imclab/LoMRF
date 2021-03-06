<pre>
    o                        o     o   o         o
    |             o          |     |\ /|         | /
    |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
    |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
    O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
                |
             o--o
    o--o              o               o--o       o    o
    |   |             |               |    o     |    |
    O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
    |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
    o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o

    Logical Markov Random Fields.
</pre>

# LoMRF: Logical Markov Random Fields

LoMRF is an open-source implementation of [Markov Logic Networks](https://en.wikipedia.org/wiki/Markov_logic_network) (MLNs) written in [Scala programming language](http://scala-lang.org).

## Features overview:

1. Parallel grounding algorithm based on [Akka Actors library](http://akka.io).
2. Marginal (MC-SAT) and MAP (MaxWalkSAT and LP-relaxed Integer Linear Programming) inference (**lomrf infer**).
3. Batch and on-line Weight Learning (Max-Margin, AdaGrad and CDA) (**lomrf wlearn**).
4. On-line Structure Learning (OSL and OSLa) (**lomrf slearn**).
5. MLN knowledge base compilation (**lomrf compile**):
  * Predicate completion.
  * Clausal form transformation.
  * Replacement of functions with utility predicates and vice versa.
  * Reads and produces [Alchemy](http://alchemy.cs.washington.edu/alchemy1.html) compatible MLN files.
6. Can export ground MRF in various formats (**lomrf export**).
7. Can compare MLN theories (**lomrf diff**).
8. Online supervision completion on semi-supervised training sets [*currently experimental*] (**lomrf supervision**)


## Documentation

Latest [documentation](docs/index.md).

## Contributions

Contributions are welcome, for details see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

LoMRF comes with ABSOLUTELY NO WARRANTY. This is free software, and you are welcome to redistribute it
under certain conditions; See the [GNU Lesser General Public License v3 for more details](http://www.gnu.org/licenses/lgpl-3.0.html).

## Reference in Scientific Publications

Please use the following BibTex entry when you cite LoMRF in your papers:
```
@misc{LoMRF,
	author = {Anastasios Skarlatidis},
	title = {{Logical Markov Random Fields (LoMRF): an open-source implementation of Markov Logic Networks}},
	url = {https://github.com/anskarl/LoMRF}
}
```