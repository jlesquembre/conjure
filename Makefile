.PHONY: compile
	
SOURCES := $(shell find src -type f)

classes: deps.edn $(SOURCES)
	rm -rf classes
	mkdir classes
	clojure -Sforce -C:fast \
		-J-Dclojure.compiler.direct-linking=true \
		-J-Dclojure.compiler.elide-meta="[:doc :file :line :added]" \
		--eval "(compile 'conjure.main)"

compile: classes
