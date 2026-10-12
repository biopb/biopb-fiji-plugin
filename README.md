# biopb-fiji-plugin

A Fiji / ImageJ2 plugin for browsing and opening tensors served by a
[biopb](https://github.com/biopb/biopb) tensor server. It is the Fiji
counterpart of [biopb-napari-widget](https://github.com/biopb/biopb-napari-widget)'s
Tensor Browser.

**Status:** scaffold. The menu entry (Plugins > BioPB > Tensor Browser) loads
but does nothing yet.

## Requirements

- **Fiji Latest** (bundles Java 21). Fiji Stable is Java 8 and cannot load the
  biopb SDK (compiled for Java 11).
- A biopb tensor server. Lazy catalog browsing needs the server and SDK from
  biopb 0.13.0rc1 (`catalog_children`).

## Build

    mvn verify

## Notes

Compatibility with Fiji's bundled jars was checked with a probe against Fiji
Latest: the SDK runs against Fiji's own imglib2/protobuf/slf4j/guava/grpc, and
needs the Arrow, Flight and netty-tcnative jars that Fiji does not ship.
Fiji's launcher unlocks `java.nio` for Arrow, so no JVM flag is needed.

## License

MIT
