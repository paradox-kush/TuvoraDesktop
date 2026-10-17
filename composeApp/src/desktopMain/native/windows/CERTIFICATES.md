# Windows playback trust bundle

`cacert.pem` is the Mozilla CA root bundle extracted by curl, shared with Tuvora's
Android and Apple playback runtimes. Its extraction date and source are in the file.
Source: https://curl.se/docs/caextract.html (Mozilla source license: MPL 2.0).
SHA-256: `f1407d974c5ed87d544bd931a278232e13925177e239fca370619aba63c757b4`.

The Gradle runtime preparation task copies this file beside libmpv and includes it
in `runtime-files.txt`. The bridge resolves it beside its own loaded DLL, including
when the runtime is extracted from a JAR. No network download or administrator
installation is needed. The bundled Windows OpenSSL runtime otherwise looks for
certificates in the machine path used when it was built.

When refreshing the Mozilla snapshot, update this checksum and verify the other
client bundles in the same maintenance pass. Never substitute an application or
provider certificate for this public-root bundle.

The on-demand Build workflow's `windows_tls` input compiles the bridge and runs
`scripts/windows/test-player-tls.py`. Its private test CA only exists in a temporary
directory and is never added to the shipped bundle or Windows certificate store.
