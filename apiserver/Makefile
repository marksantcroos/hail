.PHONY: install-hail-locally build run run-hail rm build-hail-shadow-jar check hailjwt

PROJECT := $(shell gcloud config get-value project)
REPO := gcr.io/$(PROJECT)
GSA_KEY_FILE := /gsa-key/privateKeyData
PY_FILES := $(shell find apiserver -iname \*.py -not -exec git check-ignore -q {} \; -print)

PYTHONPATH := $${PYTHONPATH:+:$$PYTHONPATH}../hail/python:../hailjwt:$(shell ls $$SPARK_HOME/python/lib/py4j-*-src.zip):$SPARK_HOME/python
PYTHON := PYTHONPATH=$(PYTHONPATH) python3

flake8-stmp: $(PY_FILES)
	$(PYTHON) -m flake8 apiserver
	touch $@

pylint-stmp: $(PY_FILES)
	$(PYTHON) -m pylint --rcfile ../pylintrc apiserver --score=n
	touch $@

ifneq ($(PY_CHECK),false)
check: flake8-stmp pylint-stmp
else
check:
endif

install-hail-locally:
	rm -rf build
	(cd ../hail && ./gradlew shadowJar)
	mkdir -p build/hail/jars
	mkdir -p build/hail/python
	cp -a ../hail/build/libs/hail-all-spark.jar build/hail/jars
	cp -a ../hail/python/hail build/hail/python

jupyter_notebook_config.py:
	sed -e "s,@project@,$(PROJECT),g" \
	    -e "s,@keyfile@,$(GSA_KEY_FILE),g" \
            < jupyter_notebook_config.py.in > $@

# FIXME rsync not in pr-builder
# rsync --delete --recursive ../hailjwt hailjwt
hailjwt:
	rm -rf hailjwt
	cp -a ../hailjwt hailjwt

ifeq ($(IN_HAIL_CI),1)
.PHONY: push deploy build-hail-base build-spark-master build-spark-worker build-apiserver

build-hail-base: install-hail-locally
	make -C ../docker build
	-docker pull $(REPO)/spark-base:latest
	-docker pull $(REPO)/hail-base:latest
	docker build -t hail-base -f Dockerfile.hail-base . --cache-from hail-base,$(REPO)/hail-base:latest,spark-base

build-spark-master: build-hail-base
	-docker pull $(REPO)/hail-base:latest
	-docker pull $(REPO)/spark-master:latest
	docker build -t spark-master -f Dockerfile.spark-master . --cache-from spark-master,$(REPO)/spark-master:latest,hail-base

build-spark-worker: build-hail-base
	-docker pull $(REPO)/hail-base:latest
	-docker pull $(REPO)/spark-worker:latest
	docker build -t spark-worker -f Dockerfile.spark-worker . --cache-from spark-worker,$(REPO)/spark-worker:latest,hail-base

build-apiserver: build-hail-base hailjwt
	-docker pull $(REPO)/hail-base:latest
	-docker pull $(REPO)/apiserver:latest
	docker build -t apiserver -f Dockerfile.apiserver . --cache-from apiserver,$(REPO)/apiserver:latest,hail-base

build-hail-jupyter: build-hail-base jupyter_notebook_config.py
	-docker pull $(REPO)/hail-base:latest
	-docker pull $(REPO)/hail-jupyter:latest
	docker build -t hail-jupyter -f Dockerfile.hail-jupyter --cache-from hail-jupyter,$(REPO)/hail-jupyter:latest,hail-base ..

build: build-spark-master build-spark-worker build-apiserver build-hail-jupyter

SPARK_MASTER_IMAGE = $(REPO)/spark-master:$(shell docker images -q --no-trunc spark-master | sed -e 's,[^:]*:,,')
SPARK_WORKER_IMAGE = $(REPO)/spark-worker:$(shell docker images -q --no-trunc spark-worker | sed -e 's,[^:]*:,,')
APISERVER_IMAGE = $(REPO)/apiserver:$(shell docker images -q --no-trunc apiserver | sed -e 's,[^:]*:,,')
HAIL_JUPYTER_IMAGE = $(REPO)/hail-jupyter:$(shell docker images -q --no-trunc hail-jupyter | sed -e 's,[^:]*:,,')

push: build
	docker tag spark-master $(SPARK_MASTER_IMAGE)
	docker push $(SPARK_MASTER_IMAGE)
	docker tag spark-master $(REPO)/spark-master:latest
	docker push $(REPO)/spark-master

	docker tag spark-worker $(SPARK_WORKER_IMAGE)
	docker push $(SPARK_WORKER_IMAGE)
	docker tag spark-worker $(REPO)/spark-worker:latest
	docker push $(REPO)/spark-worker

	docker tag apiserver $(APISERVER_IMAGE)
	docker push $(APISERVER_IMAGE)
	docker tag apiserver $(REPO)/apiserver:latest
	docker push $(REPO)/apiserver

	docker tag hail-jupyter $(HAIL_JUPYTER_IMAGE)
	docker push $(HAIL_JUPYTER_IMAGE)
	docker tag hail-jupyter $(REPO)/hail-jupyter:latest
	docker push $(REPO)/hail-jupyter

deploy: push
	sed -e "s,@spark_master_image@,$(SPARK_MASTER_IMAGE),g" \
	  -e "s,@spark_worker_image@,$(SPARK_WORKER_IMAGE),g" \
	  -e "s,@apiserver_image@,$(APISERVER_IMAGE),g" \
	  -e "s,@hail_jupyter_image@,$(HAIL_JUPYTER_IMAGE),g" \
	  < deployment.yaml.in > deployment.yaml
	kubectl -n default apply -f deployment.yaml
else
build: install-hail-locally jupyter_notebook_config.py hailjwt
	make -C ../docker build
	docker build -t hail-base -f Dockerfile.hail-base .
	docker build -t spark-master -f Dockerfile.spark-master .
	docker build -t spark-worker -f Dockerfile.spark-worker .
	docker build -t apiserver -f Dockerfile.apiserver .
	docker build -t hail-jupyter -f Dockerfile.hail-jupyter .
endif

build-hail-shadow-jar:
	cd ../hail && ./gradlew shadowJar

# to create spark network, run:
# docker network create spark
run:
	docker run --rm -d -p 8080:8080 -p 7077:7077 --network spark --name spark-master --hostname spark-master spark-master
	docker run --rm -d -p 8081:8081 --cpus 2 -m 4g --network spark --name spark-w-1 spark-worker

run-hail:
	docker run --rm -it -p 4040:4040 --network spark spark-hail /bin/bash

rm:
	docker rm -f spark-master spark-w-1

# doesn't push
run-hail-jupyter-pod: HAIL_JUPYTER_IMAGE=$(shell kubectl get deployment apiserver -o jsonpath='{.metadata.annotations.hail-jupyter-image}')
run-hail-jupyter-pod:
	sed -e "s,@hail_jupyter_image@,$(HAIL_JUPYTER_IMAGE),g" \
	  < hail-jupyter-pod.yaml.in > hail-jupyter-pod.yaml
	kubectl create -f hail-jupyter-pod.yaml

jwt-test-user-token: jwt-test-user.json jwt-test-secret-key create_key.py
	$(PYTHON) create_key.py jwt-test-secret-key < $< > $@

test: build-hail-shadow-jar check jwt-test-user-token
	PYTHONPATH=$(PYTHONPATH) ./test-apiserver.sh

clean:
	rm jupyter_notebook_config.py
	rm -rf hailjwt
	rm -rf jwt-test-user-token
