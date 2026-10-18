(ns emmy.viewer.clerk-bundle
  "Entry point of a Clerk viewer bundle with every Emmy-Viewers viewer: Clerk's
  SCI environment plus [[emmy.viewer.sci/install!]]. Pass it as
  `:cljs-namespaces '[emmy.viewer.clerk-bundle]` to `emmy.clerk/build!` or
  `emmy.clerk/serve!` to compile the bundle from source with shadow-cljs (the
  `:cljs` alias); [[emmy.viewer.export/export-notebook!]] does so by default."
  (:require [emmy.viewer.sci]
            [nextjournal.clerk.sci-env]))

(emmy.viewer.sci/install!)
