![pallet logo](https://github.com/downloads/hugoduncan/pallet/pallet-logo.png)

[Pallet](https://github.com/hugoduncan/pallet) is used
to provision and maintain compute nodes, and aims to solve the problem of
providing a consistently configured running image across a range of clouds.  It
is designed for use from the [Clojure](http://clojure.org) REPL, from clojure
code, and from the command line.

This repository contains the core pallet crates.

## Usage

The simplest way to use these crates is to make your project depend on the
`pallet-crates-all` artifact (or `pallet-crates-standalone` if you want a
single jar).

In order to get fine grained dependency control, you can depend on the
individual crates that you use.

## Support

[On the group](http://groups.google.com/group/pallet-clj), or #pallet on freenode irc.

## Installation

pallet-crates is distributed as a set of jars, and is available in the [sonatype repository](http://oss.sonatype.org/content/repositories/releases/org/cloudhoist).

Installation is with maven, lein, cake, or your favourite maven repository aware
build tool.


## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

[Contributors](https://www.ohloh.net/p/pallet-clj/contributors)

Copyright 2010 Hugo Duncan.
