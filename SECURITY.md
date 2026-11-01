# Security and data handling

Default debug and release builds embed empty service URLs and cloud API keys.
For local debugging, `-PembedLocalServiceConfig=true` reads service values from
`local.properties` into debug builds only; do not distribute debug APKs built
with personal credentials.
Users can enter their own MCP endpoints and cloud key in Settings → Service
connections. Credentials are masked by default. Editing requires stopping the
assistant so the next session uses a consistent configuration.

Camera inference, local language inference and speech recognition run on the
phone. Network tools send their required parameters to configured MCP services.
Cloud vision descriptions send the selected image to the configured provider.
Detailed debug logs can contain tool requests and model responses; review logs
before sharing them.

Cloud credentials are held in app-private preferences and excluded from Android
cloud backup and device transfer. A compromised/rooted device remains outside
that storage boundary.

Please report security issues privately to the project maintainer through the
contact route on https://nihildigit.dev/. Include affected versions and a minimal
reproduction using synthetic data. Keep real keys and personal recordings out
of public issues.
