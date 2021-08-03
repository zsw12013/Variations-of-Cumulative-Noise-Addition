# Optimizing the Trade-off Between Data Privacy and Classification Accuracy in Data Stream Mining

This repository contains experimentation with variations of cumulative noise additions performing
privacy-preserving data stream mining. Adaptive random forest for the classification and known I/O attacks to measure the privacy has been used.
The objective of this work is to control the maximum noise level of cumulative noise addition by in- cooperating different techniques.
## Quickstart

If you have Docker installed, you can run the experiments contained
within this codebase by executing `make jupyter`, opening the returned
URL in a web browser, and executing the contents of the provided
Jupyter notebooks (This has only been tested on an Ubuntu 16.04 host
running Docker 17.05.0-ce).

You will need to run the notebooks in the "dataset-construction"
sub-folder before the notebooks that depend on those datasets. 

## Dependencies

* Java (>= 1.8.0)
* Leiningen (>= 2.0)


## Further Usage

See Makefile commands
