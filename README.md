# biopb-fiji-plugin

A Fiji / ImageJ2 plugin for browsing and opening tensors served by a
[biopb](https://github.com/biopb/biopb) tensor server. It is the Fiji
counterpart of [biopb-napari-widget](https://github.com/biopb/biopb-napari-widget)'s
Tensor Browser. Tensors open as lazy (virtual) hyperstacks: planes are read from
the server when you look at them.

## Requirements

- **Fiji Latest** (bundles Java 21). **Fiji Stable (Java 8) is not supported**:
  the biopb SDK is compiled for Java 11.
- A biopb tensor server. Lazy catalog browsing needs the server and SDK from
  biopb 0.13.0rc2 (`catalog_children`, TLS trust).

## Install

- **Update site** (once published): in Fiji, *Help > Update... > Manage update
  sites*, tick the BioPB site, apply the changes and restart Fiji.
- **From Maven Central** (until the update site is live; needs Maven and a published release).
  Save this as `pom.xml` in an empty directory, set `VERSION` to the release, and run the
  command below. The parent pom pins the same dependency versions Fiji ships, and
  `includeArtifactIds` limits the copy to the jars a pristine Fiji lacks (never overwrite
  Fiji's own netty/grpc/protobuf):

      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <parent><groupId>org.scijava</groupId><artifactId>pom-scijava</artifactId><version>43.0.0</version></parent>
        <groupId>local</groupId><artifactId>biopb-fiji-install</artifactId><version>1</version>
        <dependencies>
          <dependency><groupId>io.github.jiyuuchc</groupId><artifactId>biopb-fiji-plugin</artifactId><version>VERSION</version></dependency>
        </dependencies>
      </project>

      mvn dependency:copy-dependencies -DoutputDirectory=/path/to/Fiji.app/jars \
        -DincludeArtifactIds=arrow-format,arrow-memory-core,arrow-memory-netty,arrow-memory-netty-buffer-patch,arrow-vector,biopb,biopb-fiji-plugin,flatbuffers-java,flight-core,grpc-context,grpc-netty,javax.annotation-api,netty-codec-socks,netty-handler-proxy

  Restart Fiji. To upgrade, delete the old `biopb-*.jar` files from `jars/` first.
- **Manual**: build and install into a Fiji.app. This copies the plugin jar and
  the dependencies Fiji lacks (Arrow, Flight, grpc-netty, ...) into the
  install:

      mvn install -DskipTests -Dscijava.app.directory=/path/to/Fiji.app -Ddelete.other.versions=true

  Fiji's own shared jars (netty, grpc-netty, ...) are left alone: the build only adds jars Fiji lacks,
  and CI checks it. Restart Fiji. Fiji's launcher unlocks `java.nio` for Arrow, so no JVM flag is needed.

## Usage

1. *Plugins > BioPB > Tensor Browser* opens the browser window (running it again
   brings the same window to the front). Closing the window does not affect images
   already opened from it: the server connection stays open until the last of those
   images is closed, and then closes.
2. Enter the server and press **Connect**: `host[:port]` (plaintext; default
   `localhost:8815`, or the `BIOPB_TENSOR_URL` environment variable), `grpc://host:port` (`grpc+tcp://` too), or
   `grpc+tls://host:port` (`grpcs://` also works) for TLS. Leave the address **empty** to
   discover the server the way the Python clients do (`BIOPB_TENSOR_URL`, then the local
   biopb control plane). The status line says whether the connection is plaintext or TLS;
   a failure is shown there and in a dialog, with what to do about it (token rejected, certificate
   changed, ...). The **Token** field (default `BIOPB_TENSOR_TOKEN`) is sent only to the address typed in
   the field; it is never logged or shown. It is refused over plaintext to a non-local host (use `grpc+tls://`;
   plaintext to `localhost`/`127.*`/`::1` is allowed). With an empty address the field is disabled:
   discovery finds the token itself (`BIOPB_TENSOR_TOKEN`, or the control plane's credential file).
3. Browse the catalog (folders load as you expand them), or type in the search box
   (searched on the server).
4. **Double-click** a tensor to open it at full resolution. Right-click it for
   "Open at coarser level…", which asks for a pyramid level. If a single plane is too
   large (the default limit is 2^28 pixels; ImageJ also cannot hold more than 2^31-1
   per array), you are offered the nearest coarser level that fits.
5. **Load ROIs** (right-click menu): annotations stored on the server for the tensor are
   added to the overlay of the already-open image (opened from the same connection; the current image if several match, else the finest level). Open the image first. The status
   line reports how many loaded, how many were skipped and why, and whether the
   server's per-tensor cap truncated the set.
6. **Metadata…** (right-click a source or tensor): opens a window that does not block the browser,
   with the source's path, shape and dtype, the source id and array id (each with a **Copy** button
   that copies the exact id), and the server's metadata for the source as selectable, indented text.
   Entries that are empty (null, blank, or only empty values) are left out, as in the napari widget.
   The metadata is read in the background; the window opens at once and shows the error if the read
   fails or the server does not provide metadata. It closes if the browser reconnects or is closed.
   Selecting a tensor also shows its source id, array id, shape, dtype and dims (with Copy buttons)
   under the tree.

### Label sets

A label set (segmentation masks stored with an image) is an ordinary tensor addressed
`<image array_id>/@labels/<name>`; as in the napari widget the path is the only thing that marks it. Sets are
shown **under their image** in the tree, as a row led by a `◉` glyph and the set's name. An image that has sets
still reads as one tensor (its shape stays on the source row, and double-click opens the image alone); "Open
all" never opens a set implicitly. A set whose image the catalog does not list still gets a `◉` row (under the
source), but is not counted as a tensor.

**Double-click** a set (or right-click > Open) to open it as a label image: a lazy virtual stack titled with the
set's name and its image's (`nuclei (0)`; `nuclei/1 (0)` for a set stored as a native level, plus `[level n]` when not 0), 8-bit when the set is uint8, 16-bit when uint16, else float32 (so ids wider than 16 bits are coerced,
and lose exactness above 2^24). When the set matches its image (the image's axes in the image's order, any RGB samples
axis left out, and each of t, c, z either the image's length or a singleton, typically the channel axis) it takes the image's plane grid (a singleton axis is
repeated, not re-read) and the image's calibration. A set stored as a native pyramid level (`.../@labels/name/1`) binds to the image level of the same size.
Otherwise, or if the image cannot be described or needs an axis override that was not given, it opens
with its own axes and calibration and the reason is written to the Log window, as the napari widget logs and
adds it at its own rank.

ImageJ cannot overlay a lazily loaded stack without reading all of it, so there is **no "Open as overlay on current
image"**: the set always opens as a separate image. Load ROIs does not apply to a set (it is refused).

### Adding and removing sources

Against a server **on this machine** (`localhost`, `127.*`, `::1`) you can add data to the catalog:
**drag a file or folder** onto the browser, or press **Add folder/file…**. The server then
discovers and catalogs what is under it. The line beside the button says when adding is
unavailable and why (not connected, or a remote server: a path is only meaningful to a server that
shares your disk). "Local" means a loopback address, so a remote server reached through an SSH tunnel
to `localhost` is treated as local too. One item per drop; a multi-selection is refused.

- A folder with more than 2000 entries asks first (a guard against dropping a home folder).
- A path inside **OneDrive** warns that cloud-synced sources are experimental, and if you go ahead the
  offline placeholder files are registered too (opening one downloads it). If the server left offline files
  or OneDrive folders out of an add, a dialog says how many; to include them, remove the folder and add it again.
- The add runs in the background and shows the number of sources registered; **Cancel add** stops it
  (sources already registered stay). One add runs at a time.
- Failures are listed in a dialog and the status line summarises (`Add data: added 3, 1 failed`).

**Right-click > Remove source…** on a top-level row that was added this way unregisters it from the
server (after asking); the files on disk are not touched. One removal runs at a time.

### Cloud / unresolved sources

A source the server has catalogued by URL only (a cloud or synced-folder placeholder, or one still
being indexed) has no tensors listed yet. Its row carries a marker instead of a shape: a cloud
glyph for a cloud file, `⋯` for one still being indexed, `[failed]` for one the server could not
register. Opening it does nothing; **double-click** it or use **right-click > Resolve** (labelled
*Load now* for a pending source and *Retry loading* for a failed one). Resolving a cloud file
**downloads the whole file** (minutes, local disk, not offline), so it asks first; the other two
do not. One resolve runs at a time (a second request is ignored with a status note). It runs in the background, shows the server's progress in the status line, and can be
stopped with **Cancel resolve** (it takes effect at the next server heartbeat, and the server may
finish the download anyway and cache it). When it ends, only that row is replaced, by the
tensors found, and the status line says how many (or why it failed). Servers that do not report a
reason treat every unresolved source as a cloud file, as the napari widget does.

### TLS

Connections go through the biopb SDK's `Connection`, so trust works as in the Python clients. For a
`grpc+tls://` server, in order:

1. `BIOPB_TENSOR_TLS_CA`: a PEM file (a private CA, or the server's own certificate); checked on every connect.
2. `BIOPB_TENSOR_TLS_FINGERPRINT`: the certificate's SHA-256, as `biopb-tensor-server cert init` prints it; checked on every connect.
3. Otherwise **trust on first use**: the first time you connect to a `host:port` its certificate is pinned,
   and a different certificate later is refused (never silently re-pinned). A server on this machine
   (loopback) is verified against the certificate it serves and is **never pinned**, so it has no entry in the
   pin file.

TLS runs on Fiji's own netty with the JDK's TLS provider (no native OpenSSL is installed). Set these in the environment Fiji is started from. Pins live in `~/.local/state/biopb/tls-known-hosts.json`
(`$BIOPB_STATE_HOME/biopb/` if `BIOPB_STATE_HOME` is set; same path on every platform): a JSON object mapping
`host:port` to the pinned certificate, **shared with the Python clients and the napari widget**, so a pin made by
one holds for all. To accept a legitimately rotated certificate, delete that `host:port` entry from the file
(or the file) and connect again; a pin-mismatch message that you did not expect may be a
man-in-the-middle, so check before you do.

### Settings

`Settings` is a small typed, in-memory store for tunables. Nothing is written to disk: the napari
widget's `napari-widget.json` holds only the ProcessImage widgets' settings, which this plugin does not
use, so no keys are registered yet and the Tensor Browser's limits are constants. A feature that needs
a tunable registers its key and default in `Settings.DEFAULTS`; persistence can be added behind
`Settings.set` later.

### Assumptions

- ROI coordinates are treated as pixel-index space of level 0 and are divided by the
  level's scale to match the image you opened. ROIs pinned to a plane via the
  annotation's axis map are placed on that c/z/t plane; unpinned ones show on all planes.
- Axes are recognised by label (t, c, z, y, x, s for a size-3 RGB sample axis), in the
  descriptor's order; data is never transposed. A tensor with unlabeled axes is
  assumed to end in (y, x). If more than two unlabeled axes are non-singleton (or a
  label is unknown) a dialog asks for an **axis override**: one letter per axis from
  `t c z y x s -` (`-` = ignored singleton), e.g. `tzyx`.
- uint8, uint16 and float32 open natively; other numeric types are converted to
  float32 plane by plane.

## Development

    xvfb-run -a mvn -B verify      # build and test (Swing tests need a display; xvfb on headless Linux)

CI (`.github/workflows/ci.yml`) runs the build, plus a `fiji-smoke` job that installs the
plugin into Fiji Latest and runs `.github/scripts/smoke.groovy` headless (command
discovery, SDK class loading, an Arrow allocation).

## Release

Push a tag `vX.Y.Z`. The release workflow builds the jar with that version and attaches
it to a GitHub Release. If the repository variable `UPDATE_SITE` is set, it also uploads
to the Fiji update site; that job runs in the `fiji-update-site` environment, which needs:

- variables `UPDATE_SITE` (the site name registered at imagej.net) and `WIKI_USER`
  (the wiki user owning the site);
- secret `IMAGEJ_PW` (that user's wiki password).

See the header of `.github/workflows/release.yml` and
<https://imagej.net/update-sites/automatic-uploads>.

## License

MIT
