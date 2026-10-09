# Product and scope

## Confirmed context

The user has a European-market 2004 Mazda RX-8 with 231 PS and a Samsung Galaxy A53; the Android version remains to be identified.
User's adapter: iLC OBD2 Bluetooth, ASIN B0BDRHRR22.
Selected priority: live data and trip recording. Prepare for a future API without developing it yet.

## Expected MVP outcome

The user selects the adapter and vehicle profile, connects, sees the available data, and starts a trip.
They can lock the screen during recording, stop recording from a notification, and view history without Internet access.

## Planned screens

| Screen | Contents |
| --- | --- |
| Connection and vehicle | Adapter, permissions, status, profile, alias, and optional VIN |
| Live data | RPM, coolant temperature, speed, and favorites from supported sensors |
| Trips | Sessions, duration, charts, connection gaps, and summary |
| Trip details | Samples, valid minimum/maximum values, export, and deletion |
| Settings | Units, sampling rate/priorities, storage, and synchronization status |

The MVP does not include maps. A trip is a telemetry session.
Distance calculated from speed must be labeled as an estimate, not the car's odometer reading.

## Candidate data and limitations

The first-series workshop manual lists RPM, speed, coolant temperature, intake air temperature, load, MAF, throttle position, fuel trims, and ignition timing advance.
This is a candidate list: each parameter must be confirmed through capability discovery and readings from the real car.

The app will attempt to read the VIN through mode 09, PID 02; it can continue without it.
Total mileage, oil temperature/pressure, and other Mazda data are not guaranteed through generic OBD.
An oil pressure switch does not provide continuous pressure in bar.
Compression requires an external measurement and will not be offered as an OBD reading.
ABS/SRS are not part of this adapter's promised compatibility.

## MVP acceptance criteria

- Works without Internet access or an API.
- Distinguishes a Bluetooth connection from successful ECU communication.
- Discovers accessible parameters and does not display errors as zero.
- Retains vehicles, sessions, and samples across restarts.
- Adding a VIN later does not split or lose history.
- Keeps an explicitly started recording running with the A53 screen locked.
- Preserves saved data after an interruption and marks the gaps.
- Exports and deletes trips with clear scope.
- Supports demonstrations using clearly identified synthetic data.
- Prepares batches for the future contract without making real uploads.

## Outside this first version

API/server/remote database, cloud accounts, GPS maps, advanced diagnostics, fault clearing, ECU reprogramming, and actuator control.
Additional Mazda sensors require specific investigation and testing before inclusion.

## References

Accessed on October 10, 2026. The Mazda manuals are reproduced on third-party sites.

- [Product, same ASIN on Amazon Italy](https://www.amazon.it/iLC-Bluetooth-Scansione-Collegare-Dispositivi/dp/B0BDRHRR22).
- [Mazda: diagnostic modes](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T02.html).
- [Mazda: parameter monitor](https://www.foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T05.html).
- [Mazda: instrument cluster, ODOCOUNT, and OPSC](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE092255430T06.html).
- [Mazda: compression inspection](https://rx8handbuch.de/esicont/en/srvc/html/BHE011002000W03.html).
- [ELM327: commands and VIN responses](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf).
