## Dev Dependencies

- [node.js](https://nodejs.org/en/)
- The [clojure command line tool](https://clojure.org/guides/install_clojure)
- [Babashka](https://github.com/babashka/babashka#installation)

## Github Pages, Docs Notebook

The project's [Github Pages site](https://emmy-viewers.mentat.org) hosts an
interactive [Clerk](https://github.com/nextjournal/clerk) notebook demonstrating
the library's use.

### Local Notebook Dev

Start a Clojure process however you like, and run `(user/serve!)` to run the
Clerk server. This command should open up `localhost:7777`.

Alternatively, run

```sh
bb clerk-watch
```

### Static Build

To test the static build locally:

```
bb publish-local
```

This will generate the static site in `public/build`, start a development http
server and open up a browser window (http://127.0.0.1:8080/) with the production
build of the documentation notebook.

### Exporting fragments and single notebooks

`emmy.viewer.export` turns a fragment (the quoted Reagent form returned by
`emmy.mafs/mafs`, `emmy.mathbox/mathbox`, `emmy.jsxgraph/jsxgraph`,
`emmy.leva/controls`, ...) into text and back:

```clojure
(require '[emmy.viewer.export :as x])

(def s (x/fragment->edn frag))   ;; canonical EDN string
(x/edn->fragment s)              ;; same form, same metadata

(def j (x/fragment->json frag))  ;; JSON for non-Clojure consumers
(x/json->fragment j)
```

The read-back fragment is `=` to the original, keeps lists and vectors apart,
and carries the same metadata, so `emmy.viewer/expand`, Clerk and Portal
render it identically. Writing is canonical: maps and sets are emitted in a
fixed order, so equal fragments give byte-identical strings (good for hashing
and diffs).

The `:nextjournal.clerk/viewer` metadata holds a Clerk viewer or a transform
function, which cannot be serialized. It is written as the qualified symbol of
a registered viewer and resolved again on read. Registered by default:
`emmy.viewer/reagent-viewer`, `emmy.mafs/default-viewer` and
`emmy.mathbox.plot/scene`. Name your own with

```clojure
(x/register-viewer! 'my.ns/my-viewer (fn [] my-viewer))
```

Exporting a fragment whose viewer is not registered, or whose form holds
anything that is not plain data (functions, atoms, records), throws.

EDN document:

```clojure
{:emmy.viewer.export/form    <quoted Reagent form>
 :emmy.viewer.export/format  "emmy-viewers/fragment"
 :emmy.viewer.export/meta    {:nextjournal.clerk/viewer emmy.mafs/default-viewer ...}
 :emmy.viewer.export/version 1}
```

JSON document: `{"format": "emmy-viewers/fragment", "version": 1, "form": J(form), "meta": J(meta)}`,
where `J` encodes each Clojure value as follows:

| Clojure                         | JSON                                      |
|---------------------------------|-------------------------------------------|
| `nil`, booleans, strings        | `null`, booleans, strings                 |
| integers / finite doubles       | numbers (integral doubles keep `.0`)      |
| `##Inf` `##-Inf` `##NaN`        | `{"~double": "Infinity"}` etc.            |
| ratio, bigint, bigdec, char     | `{"~ratio": "1/2"}`, `{"~bigint": "1"}`, `{"~bigdec": "1.5"}`, `{"~char": "c"}` |
| keyword `:ns/k`                 | `{"~kw": "ns/k"}`                         |
| symbol `ns/s`                   | `{"~sym": "ns/s"}`                        |
| vector `[a b]`                  | array `[J(a), J(b)]`                      |
| list `(a b)`                    | `{"~list": [J(a), J(b)]}`                 |
| map `{k v}`                     | `{"~map": [[J(k), J(v)], ...]}`           |
| set `#{a}`                      | `{"~set": [J(a), ...]}`                   |

Every encoded JSON object has exactly one key, which is one of these tags, so
plain strings never need escaping. Map keys may be any value. A JS consumer
sees `1` and `1.0` as the same number; the Clojure reader keeps them apart.

To build ONE notebook into a self-contained static folder (same pipeline as
the static build below, through `emmy.clerk/build!`), from a REPL started
with the `:nextjournal/clerk` alias:

```clojure
(x/export-notebook! {:notebook "dev/examples/mafs.clj"
                     :out-dir  "public/lesson"})
;; => {:notebook ... :out-dir "public/lesson" :index-html "public/lesson/index.html"}
```

Other options go to `emmy.clerk/build!` and Clerk's `build!`, e.g.
`:package :single-file`. By default the page loads Emmy-Viewers' published JS
bundle from Clerk's CDN (`emmy.clerk/custom-js`), so viewing it needs network
access; pass `:cljs-namespaces '[emmy-viewers.sci-extensions]` to compile the
bundle locally instead (needs node). The test suite does not run
`export-notebook!`.

### GitHub Pages

To build and release to Github Pages:

```
bb release-gh-pages
```

This will ship the site to https://emmy-viewers.mentat.org.

## Publishing to Clojars

The template for the project's `pom.xml` lives at
[`template/pom.xml`](https://github.com/mentat-collective/emmy-viewers/blob/main/template/pom.xml).

To create a new release:

- Update the version in
  [build.clj](https://github.com/mentat-collective/emmy-viewers/blob/main/build.clj)
- Make a new [Github
  Release](https://github.com/mentat-collective/emmy-viewers/releases) with tag
  `v<the-new-version>`.

Submitting the release will create the new tag and trigger the following
command:

```
bb release
```

The new release will appear on Clojars.

## Publishing JS

> **Note**
> To publish `emmy-viewers` JS, you must be part of the [Mentat Collective
> GitHub organization](https://github.com/mentat-collective).

- create a classic personal access token with `read:org` permissions.
- Add a line like this to your bash profile:

```bash
export GITHUB_TOKEN="<your_token>"
```

Run the following code with the `:nextjournal/clerk` alias activated:

```clojure
(require '[mentat.clerk-utils.build :as b])

(b/release->cas!
 {:cljs-namespaces ['emmy-viewers.sci-extensions]
  :cas-namespace "mentat-collective"
  :prefix "emmy-viewers"
  :token (System/getenv "GITHUB_TOKEN")})
```

- Take the resulting URL and replace the existing entry at
  `emmy.clerk/custom-js` with the new URL that prints.

## Linting

Code is linted with [`clj-kondo`](https://github.com/clj-kondo/clj-kondo):

```
bb lint
```
