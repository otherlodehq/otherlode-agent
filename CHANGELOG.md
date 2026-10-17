# Changelog

All notable changes to the agent and the testkit, which share a version, are recorded here in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

`scripts/release.sh` and the release workflow both require a `## [X.Y.Z]` section with a body for each release.
Keep link reference definitions (`[0.1.0]: https://...`) at the bottom of the file: the first one ends
the section being read.

## [Unreleased]

### Added

- `disabledEndpointModules` keeps single endpoint modules from installing, by name, separated by `;`.
- A module the configuration keeps off, by `disabledEndpointModules` or by `endpointsEnabled=false`, is reported as disabled with the kind `CONFIGURED_OFF`, in the wire format and in the testkit.
