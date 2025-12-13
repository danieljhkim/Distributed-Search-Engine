# Common Failures

## Health check fails
- Confirm runtime is up: .box/scripts/up.sh
- Check logs: .box/scripts/logs.sh --tail 200

## Tests fail
- Inspect .box/reports/: junit / coverage
- Check logs relevant to the failing component
