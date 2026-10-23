## Purpose

Finds DLNA/UPnP MediaRenderers on the local network and lets the user choose one, remembering the choice so repeated casting needs no extra taps.

## ADDED Requirements

### Requirement: Discover MediaRenderers
The app SHALL discover devices of type `urn:schemas-upnp-org:device:MediaRenderer:1` on the Wi-Fi network using SSDP, and SHALL list each discovered renderer by its friendly name. Only renderers exposing an AVTransport service SHALL be listed. Discovery SHALL show results as they arrive and SHALL be repeatable by the user.

#### Scenario: Renderer on the network
- **WHEN** the picker opens and the PC renderer "TV" is running on the same Wi-Fi network
- **THEN** "TV" appears in the list within 5 seconds

#### Scenario: No renderers
- **WHEN** discovery finishes and no renderer answered
- **THEN** the picker says that no TV was found, suggests checking that both devices are on the same network, and offers to search again or add a renderer manually

### Requirement: Remember the last renderer
The app SHALL remember the last renderer the user cast to, by its unique device name (UDN) and last known description URL, and SHALL consider it available if it answers discovery or its description URL responds within 3 seconds.

#### Scenario: Renderer changed IP address
- **WHEN** the remembered renderer comes back with a new IP address and answers SSDP with the same UDN
- **THEN** the app uses the new address without asking the user

### Requirement: Manual renderer
The user SHALL be able to add a renderer by entering its description URL (for example `http://192.168.50.23:49494/description.xml`). The app SHALL validate it by fetching the description and SHALL reject URLs that do not describe a renderer with AVTransport.

#### Scenario: Multicast blocked on the router
- **WHEN** SSDP finds nothing and the user enters the renderer's description URL
- **THEN** the renderer is added to the list and can be selected and remembered like a discovered one

#### Scenario: Wrong URL
- **WHEN** the user enters a URL that does not respond or does not describe a MediaRenderer
- **THEN** the app shows an error and does not add it

### Requirement: Wi-Fi required
Discovery and casting SHALL require an active Wi-Fi (or Ethernet) connection. Without one, the app SHALL tell the user instead of searching.

#### Scenario: Mobile data only
- **WHEN** the user shares a photo while connected only to mobile data
- **THEN** the app says that a Wi-Fi connection to the TV's network is required
