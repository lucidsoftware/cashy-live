#
# The Lucidchart mailing-service build and release process
#
#

SHELL := /bin/bash -e

.PHONY: default
default: all

.PHONY: all
all : clean compile test stage

.PHONY: package
package:
	build/createpackage $(VERSION_PREFIX)

.PHONY: deploy
deploy:
	build/deploy

.PHONY: test
test: export TESTING_DB_HOSTNAME=ci-testserver.lucidchart.com
test:
	sbt test

.PHONY: %
%:
	sbt $*
