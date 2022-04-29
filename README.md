# C3RES

C3RES is a project for allowing distributed and secure storage of private data.
The name stands for "Content-addressed Cryptographically-secured
Capability-based REsource Store". More info at [c3res.org](http://c3res.org).

## General information

This is an early prototype implementation of the idea presented on the
[C3RES](http://c3res.org) website. It consists of a client and a server where
the client acts as an access point to the data stored fully encrypted either in
a local cache or on the server. Only the client has access to the encryption
keys (via a capability system) and all the data stored on the server can be
exposed without compromising the contents (except for a small amount of
metadata which is stored in a database on the server).

A simple architecture schetchup (mostly still unimplemented...):

```
        +--------------+                    +--------------+
        | SHARD SERVER | <-- federation --> | SHARD SERVER |
        +--------------+                    +--------------+
                |
                .
                .
                .
                |                      ----+
        +---------------+                  |
        | CLIENT DAEMON |                  |
        +---------------+                  |
          |      |    |                    |
+-------------+  |  +------------+          `
| TERMINAL UI |  |  | HTTP QUERY |           }-- On a local machine
|    / CLI    |  |  +------------+          ,
+-------------+  |                         |
             +-------+                     |
             |  GUI  |                     |
             +-------+                     |
                                       ----+
```

## Why ClojureScript??

Three reasons: I like Clojure, the nodejs ecosystem has lots of similar
projects (and thus libraries), and the whole client is one day supposed to also
run in a browser.

That said, the main purpose of this prototype is to act as a proof of concept
and help refine that concept further based on real life experiences when
implementing it. Nodejs and ClojureScript itself aren't likely a very good fit
for a serious server implementation, but the main goal here is to end up with a
system that works, which can then be formalized in a specification document.

Based on that specification further implementations should then be possible and
the current plan is to replace at least the server component later with a
native implementation written in a statically typed, fast, and memory safe
language like Rust, Nim, or even modern C++.

