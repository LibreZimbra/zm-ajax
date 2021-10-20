# SPDX-License-Identifier: AGPL-3.0-or-later

ANT_TARGET = $(ANT_ARG_BUILDINFO) publish-local

all: build-ant

include build.mk

install:
	cp build/zm-ajax-*.jar zm-ajax.jar
	$(call install_jar_lib, zm-ajax.jar)
	$(call mk_install_dir, include/zm-ajax)
	cp -R WebRoot src $(INSTALL_DIR)/include/zm-ajax

clean: clean-ant
