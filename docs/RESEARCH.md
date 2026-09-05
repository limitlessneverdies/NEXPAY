# Design and platform research

Consulted during implementation, 5 September 2026. These sources inform the choices; they do not certify this code.

- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions — Android 12+ requires runtime Scan, Connect and Advertise permissions for the respective operations; older Android uses the legacy permissions/location constraints.
- Android Wi-Fi Direct: https://developer.android.com/develop/connectivity/wifi/wifip2p — Android 13+ Nearby Wi-Fi Devices, older fine location; relevant discovery APIs can still depend on Location mode. Wi-Fi Direct uses local sockets despite not requiring the internet.
- Android Wi-Fi permissions: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- BIS Project Polaris design guide: https://www.bis.org/publ/othp79.htm — offline payment solutions involve hard security and operational trade-offs, not a universal finality guarantee.
- Nepal Rastra Bank Payment Systems Department: https://www.nrb.org.np/departments/psd — NRB licenses and regulates payment service providers and system operators. This project has no such authorization or partnership.
- Wise design references: https://wise.design/ and https://docs.wise.design/ — reviewed publicly available search descriptions for clarity, restrained hierarchy and reduced distraction. The docs page returned only a redirect. No competitor screen was visually inspected or copied.

No reference image attachment was actually present. The Paila wordmark, cobalt/neutral palette, layout, native screens and landing design were created for this package. Branding/trademark clearance has not been performed.
