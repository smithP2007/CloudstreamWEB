# Third-party notices

Tools downloaded at bootstrap time (never committed — see `plugin-runtime/tools/`
in `.gitignore`; each keeps its own upstream license):

| Tool | Source | License | Purpose |
| --- | --- | --- | --- |
| dex2jar v2.4 | [pxb1988/dex2jar](https://github.com/pxb1988/dex2jar) | Apache-2.0 | dex → JAR conversion with annotations intact (needed for `@JsonProperty`-driven providers when loading `.cs3`) |
| enjarify | [Storyyeller/enjarify](https://github.com/Storyyeller/enjarify) | Apache-2.0 | fallback dex → JAR converter for cases dex2jar fails on |

Runtime dependencies carry their own licenses and are pulled via Gradle/npm:
Ktor & okhttp (Apache-2.0), SQLite JDBC, React (MIT), **hls.js** (Apache-2.0),
Vite/toolchain (MIT).

The CloudStream upstream library (`upstream/` submodule,
[recloudstream/cloudstream](https://github.com/recloudstream/cloudstream)) and
this port are GPL-3.0.
