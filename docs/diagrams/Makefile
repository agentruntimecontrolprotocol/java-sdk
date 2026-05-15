DOTS := $(wildcard *.dot)
SVGS := $(DOTS:.dot=.svg)

all: $(SVGS)

%.svg: %.dot
	dot -Tsvg $< -o $@

clean:
	rm -f $(SVGS)

.PHONY: all clean
