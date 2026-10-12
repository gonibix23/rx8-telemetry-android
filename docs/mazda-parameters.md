# Mazda-specific parameter research (RX8-009)

Reviewed October 12, 2026 for the owner's European 2004 RX-8, 231 PS, using the existing Bluetooth adapter. This is the evidence matrix for [issue #9](https://github.com/gonibix23/rx8-telemetry-android/issues/9). **No proprietary query is enabled by this research.** Car checks are explicitly deferred; the current standard acquisition and offline recorder remain unchanged.

## Decision and evidence levels

Prioritize knock retard and trailing ignition timing for an isolated decoder/transport prototype, then oil metering pump and intake valve status once their wire definitions are established. Odometer and cluster fuel flow are worthwhile but lack an accepted query. Optional TPMS depends on installed equipment and conflicting published scaling. A manual's diagnostic mnemonic is not a wire identifier.

- **Documented meaning:** Mazda describes the signal, but does not provide request/response bytes or scaling.
- **Published wire candidate:** an original researcher provides an identifier/conversion; compatibility, payload framing and missing-value behavior still require verification.
- **Tested in this app/car:** none of the proprietary candidates below. Synthetic tests will establish decoder behavior only.
- **Unavailable as requested:** a switch cannot supply continuous pressure, and compression needs external measurement. This does not assert that every other undocumented signal is impossible.

Only a separately reviewed, documented and verified read-only definition may enter production. No identifier scan, actuator test, security unlock, fault clearing or reprogramming is part of the plan.

## PCM candidates

All rows below are **documented meaning / wire definition pending / car test pending**, except the additional published candidates listed in the next section. Units describe the diagnostic tool's output, not a proven raw-byte conversion. Mazda's [PCM monitor table](https://www.foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T05.html) is primary Mazda material hosted on a third-party mirror; its 2003 manual context does not establish the owner's calibration.

| Monitor family | Meaning and unit | Priority / distinction |
| --- | --- | --- |
| MOP_POS / MOP_SW | Oil metering control status, unspecified unit / pump switch, On/Off | High; neither is oil pressure or measured oil flow |
| SPARK-L / SPARK-T / KNOCKR | Leading/trailing advance and knock retard, degrees | High; preserve separate meanings |
| APV / APV_POS | Motor command / position-sensor voltage, V | High; voltage is not an opening percentage |
| SSV / DEI / IASV | SSV / VDI / VFAD solenoid commands, On/Off | High; command state is not physical valve feedback |
| FAN1 / FAN2 | Fan relay commands, On/Off | High; no measured fan speed |
| FUELPW / ARPMDES | Injection duration, ms / target RPM | Medium |
| APP1/2 / ETC_ACT/DSD | Pedal %, V / actual and desired throttle, degrees or % | Medium; retain units |
| BOO / CPP / CPP/PNP / INGEAR | Brake, clutch, neutral and gear state | Medium; not a gear number |
| GENVDSD / PCM_T | Target generator V / PCM temperature-sensor V | Lower; PCM_T is not degrees Celsius |

Standard RPM, speed, coolant, MAF mass flow and fuel trims already use advertised Mode 01 support. Do not introduce proprietary aliases solely to duplicate them or rename generic ignition timing as proven leading timing.

The toolmaker [VersaTuner's Series 1 logging list](https://www.versatuner.com/features/mazda-rx-8-gen1) independently lists oil metering, valve status, knock and both ignition timings. It advertises generic Bluetooth ELM327 for low-speed logging; this supports feasibility, not this adapter's compatibility, a sample-rate guarantee or a published decoding contract. Some listed channels are beta. Tuning features are outside scope.

## Published PCM wire candidates

Original [RX8Defs definitions at commit 607d1a3](https://github.com/equinox311/RX8Defs/blob/607d1a3feea82556c9c727344b141b1e2bf637f5/RomRaider/logger_rx8_defs.xml) specify 500 kbit/s ISO 15765, PCM request `7E0` / response `7E8`. These are **candidates, not accepted definitions**; `x` is the unsigned payload value.

| Signal | Service + identifier | Published payload/conversion | Unit | Status |
| --- | --- | --- | --- | --- |
| Knock retard | `22 17 46` | uint8; `x * 0.3515625` | degrees | First prototype candidate |
| Trailing ignition timing | `22 09 04` | uint8; `x * 0.5 - 64` | degrees | First prototype candidate |
| Leading ignition timing | `22 00 0E` | uint8; `x * 0.5 - 64` | degrees | Verify meaning against standard timing |
| Tire revolutions per mile | `22 16 F0` | uint16; `x` | rev/mile | Not odometer; byte order pending |
| Injector duration / pedal | Both `22 14 10` | Conflicting meanings and uint8/uint16 widths | ms / % | Reject until resolved |

The file contains duplicate IDs, custom flex-fuel entries and a roughly 2007-onward transport label. Do not import it wholesale; captures, sentinels and calibration compatibility are absent.

The author's [response processor at commit 3cf43e2](https://github.com/equinox311/GROM_RomRaider/blob/3cf43e2ba6282de307932b167119a1ff91d38593/src/main/java/com/romraider/io/protocol/mazda/iso15765/MAZDAResponseProcessor.java) recognizes `62` and skips service/identifier bytes in J2534-formatted responses. This corroborates the framing expectation at source level, **not an observed iLC response**. Echo/source validation, exact payload length, byte order and rejection behavior still need independent fixtures. Its experimental initialization and negative-response handling are not a production recipe.

The [original author's project explanation](https://www.rx8club.com/series-i-engine-tuning-forum-63/open-source-s1-rx-8-ecu-reverse-engineering-data-logging-tuning-users-guide-276137/) describes an experimental logger and work concentrated on particular ROMs. It lists a 2004 EU six-port calibration among available definitions, but that does not establish the owner's PCM revision or each logger entry. ROM memory addresses and tuning definitions are not interchangeable with read-only diagnostic identifiers. We link the original sources without redistributing their XML, ROMs or transmission code.

## Cluster and optional modules

| Candidate | Module / unit | Evidence state | Missing evidence |
| --- | --- | --- | --- |
| ODOCOUNT / ODO COUNT | Instrument cluster; total distance | Documented meaning; query and car tests pending | Header, service, identifier, width, scale, rollover and units |
| FUEL | Instrument cluster; flow, L/min | Documented meaning; query and car tests pending | Full wire definition; not tank level |
| OPSC | Instrument cluster; oil-pressure switch/gauge status | Documented discrete indication | Polarity/encoding; never convert to bar or psi |
| ECT_GAUGE | Instrument cluster; indicated coolant temperature | Documented meaning | Wire definition; distinguish gauge indication from PCM coolant |
| Tire pressure / temperature | Optional TPMS; four sensor slots | Original community wire candidates | Installed module, response header, bytes, units and scaling |
| LF/LR/RF/RR_WSPD / SWA POS | ABS/DSC wheel speeds, km/h or mph / DSC steering angle, degrees | Mazda monitor names; vendor reports broadcasts | Diagnostic wire definition or separately verified broadcast decoder |
| MCYLIP / LAT ACC | DSC master-cylinder pressure / lateral acceleration | Mazda monitor names | Wire definition, unit/scale and equipment match |
| TRQ_SENS / EPS_MTR / EPSLAMP | EPS steering torque, N m / motor current, A / lamp state | Mazda monitor names | Wire definition, sign/scale and missing-value rules |
| ABS / SRS diagnostics | Separate modules | No accepted support in this app | Module access and documented read-only queries |

Mazda's [cluster service highlights](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE092255430T06.html) list ODOCOUNT in km/miles, FUEL in L/min and OPSC as a switch. The [workshop Body and Accessories manual, PDF page 407](https://www.rotaryheads.com/PDF/RX8/11Body.pdf#page=407) instead presents ODO COUNT in metres and OPSC as gauge status. Preserve this variation as unresolved display/scaling evidence. Neither table supplies diagnostic request bytes; a dashboard circuit-test number is not a PID.

The [Mazda Brakes manual, PDF pages 84–86 and 103–106](https://www.rotaryheads.com/PDF/RX8/7Brakes.pdf) and [Steering manual, PDF pages 35–36](https://www.rotaryheads.com/PDF/RX8/8Steering.PDF) establish the chassis monitor names above, not their wire decoders. Availability depends on fitted ABS/DSC equipment. Lifetime odometer must remain distinct from integrated trip distance or a broadcast distance-increment counter.

In the [original Torque author thread, posts 1, 4 and 6](https://www.rx8club.com/series-i-tech-garage-22/validated-torque-pids-250627/), MAF voltage is proposed as `22 11 77`, header `7E0`, unsigned two-byte value divided by 3700 V. TPMS uses header `751`, `22 C9 01` for pressure, data bytes A–D at `1.373 * byte` kPa, and `22 C9 02` for temperature. Its Fahrenheit conversion implies `byte - 50` Celsius, while a later Celsius instruction says `byte` alone: **temperature scaling conflicts**. Response headers/captures are absent. Sensor slots are not established wheel locations. Keep these as pending leads, with no live implementation.

[Racelogic's RX-8 CAN document](https://www.racelogic.co.uk/_downloads/vbox/Vehicles/Other/Docs/Mazda-RX-8.pdf) describes listening to 500 kbit/s traffic on OBD pins 6/14, including wheel speeds and steering angle. It cautions about variants and supplies no frame IDs/scaling in that document. Broadcast reception is a separate acquisition design, not proof of an accessible OBD sensor or ABS diagnostic support.

## Transport and acceptance boundary

Mazda's [Vehicle Networks training guide, pages 11–12 and 24](https://www.rx8club.com/attachments/series-i-trouble-shooting-95/tsbs-technical-service-bulletins-183774/mazda-vehicle-networks-181308d1328911101) marks the 2004-onward RX-8 with ISO and HS-CAN, not MS-CAN; its gateway example concerns the Mazda3. Do not assume a Series 1 cluster requires an MS-CAN switch or Mazda3 gateway. The owner's reported 11-bit, 500 kbit/s connection still establishes only the successful existing diagnostic path.

The [Mazda workshop communication flowchart, PDF page 423](https://www.rotaryheads.com/PDF/RX8/11Body.pdf#page=423) distinguishes CAN paths for PCM/EPS/cluster from K-line diagnostic paths for ABS/DSC. A module broadcasting on CAN does not establish that its diagnostic requests use that bus. SRS/module access remains a separate unverified scope.

The official [ELM327 data sheet](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf) documents header selection, receive filtering and CAN flow control. Compatibility of this adapter's implementation remains unverified for enhanced requests. The current app supports serialized Mode 01 acquisition and a narrowly bounded VIN reconstruction; it has no general proprietary-response assembler or raw-bus stream. Mode 01 capability bitmaps do not advertise Mazda service-22 identifiers.

Before a candidate is accepted, its record must contain:

1. Module, market/model/PCM revision evidence, source URL and immutable source revision where available.
2. Request header/service/identifier, expected response source and echoed bytes, exact lengths and transport framing. Do not guess headers from module names.
3. Payload offsets, signedness, byte order, formula, canonical unit, domain, missing/sentinel rules and negative-response behavior.
4. Independently reviewed anonymized request/response fixtures and meaningful parser tests, including truncation, wrong source/echo, invalid values, timeouts and interleaving.
5. Later owner validation of support and plausibility on the actual car, separately from synthetic decoding results. Keep private identifiers and captures out of GitHub.

## Work that can proceed before car validation

- Design an explicit read-only definition allowlist and per-module response routing. Preserve automatic protocol selection; serialize commands, bound response sizes/retries and restore the standard session's addressing after an optional request.
- Build an isolated knock/trailing-timing parser prototype from clearly synthetic fixtures. Treat expected response bytes as assumptions until independent captures establish them; no automatic probing or production polling yet.
- Extend response assembly only as needed by a reviewed definition. General ISO-TP, K-line module access and passive CAN reception require separate designs and tests.
- Obtain trustworthy MOP, intake-valve and cluster wire definitions; resolve injector and TPMS conflicts rather than choosing a convenient formula.
- Plan stable metric IDs, source/quality/unit handling, booleans/enums and any API/catalog changes before recording new fields. Live/overlay selection must remain independent of polling and saved fresh outcomes; unavailable values remain null.

Continuous oil pressure is unavailable from OPSC; oil temperature is not established by these sources. Do not synthesize either from coolant or pump status. Mazda's [compression inspection procedure](https://rx8handbuch.de/esicont/en/srvc/html/BHE011002000W03.html) uses an external compression tester, so compression is not an OBD dashboard candidate.

## Issue status

The documented/published/pending/unavailable matrix is now maintained here. **Accepted proprietary queries: none. App/car-tested proprietary queries: none.** Research establishes useful next targets without completing their compatibility checks. Keep #9 open for accepted definitions and later validation; the owner-deferred car tests do not block documentation or isolated software development.
