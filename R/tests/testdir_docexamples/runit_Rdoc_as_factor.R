setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"-f")))
source('../findNSourceUtils.R')

test.rdocasfactor.golden <- function(H2Oserver) {
	
#Example from as.factor R example

prosPath = system.file("extdata", "prostate.csv", package="h2oRClient")
prostate.hex = h2o.importFile(H2Oserver, path = prosPath)
prostate.hex[,4]=as.factor(prostate.hex[,4])
sum<- summary(prostate.hex[,4])
is<- is.factor(prostate.hex[,4])

Log.info("Print output from as.data.frame call")
Log.info(paste("H2O Summary  :" ,sum))

expect_true(is)


testEnd()
}

doTest("R Doc as factor", test.rdocasfactor.golden)

