# Changelog

## Unreleased

### Breaking

- Hail band and frequency config key `:spawn` renamed to `:spawn-session`. The old key is no longer read; update band declarations and hail frequency maps accordingly. The internal delivery action keyword `:spawn` is unchanged.