SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
APK        := app/build/outputs/apk/debug/app-debug.apk

export JAVA_HOME ?= /usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME ?= $(HOME)/Android/Sdk
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: build test install adb-install push-model clean

build:
	./gradlew assembleDebug
	@echo "APK: $(APK)"

test:
	./gradlew testDebugUnitTest

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/phone-whisper.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/phone-whisper.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ANDROID_HOME)/platform-tools/adb install -r -d $(APK)  # -d: CI builds carry higher versionCodes

## Push a model to the phone's internal storage (usage: make push-model MODEL=/path/to/model-dir)
push-model:
	@test -n "$(MODEL)" || (echo "Usage: make push-model MODEL=/path/to/model-dir" && exit 1)
	$(ANDROID_HOME)/platform-tools/adb shell rm -rf /data/local/tmp/$(notdir $(MODEL))
	$(ANDROID_HOME)/platform-tools/adb push $(MODEL) /data/local/tmp/$(notdir $(MODEL))
	$(ANDROID_HOME)/platform-tools/adb shell "run-as com.kafkasl.phonewhisper mkdir -p files/models/$(notdir $(MODEL))"
	@for f in $$(ls $(MODEL)/*.onnx $(MODEL)/*.ort $(MODEL)/*.txt $(MODEL)/*.data 2>/dev/null); do \
		echo "  copying $$(basename $$f)..."; \
		$(ANDROID_HOME)/platform-tools/adb shell "run-as com.kafkasl.phonewhisper cp /data/local/tmp/$(notdir $(MODEL))/$$(basename $$f) files/models/$(notdir $(MODEL))/$$(basename $$f)"; \
	done
	@echo "Model pushed: $(notdir $(MODEL))"

clean:
	./gradlew clean
