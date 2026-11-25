[New Text Document.txt](https://github.com/user-attachments/files/35063591/New.Text.Document.txt)
================================================================
  UNOFFICIAL TD-H9 PATCH - V1.0.38.P1
  Scan Speed + APRS Mute + Battery %
================================================================
 
READ THE WARNING AT THE BOTTOM OF THIS FILE BEFORE FLASHING.

  DOES NOT WORK WITH MY APRS APP
  
WHAT IT DOES
A small patch to the official TIDRADIO TD-H9 firmware V1.0.38. It
keeps the stock radio and menus and adds three things:
 
  - Scan Speed: a 7th line in the Scan menu with Slow / Normal /
    Fast. Your choice is saved and restored at power-on.
 
  - B-side APRS Mute: APRS packets on channel B stay out of the
    speaker but are still decoded. This only happens while APRS is
    on and APRS RX CH is set to CH-B. With APRS off, channel B works
    exactly like stock (handy when B is your scanning channel). An
    APRS Mute line in the APRS menu (Off / On, saved) turns the
    mute off completely.
 
  - Battery Percent: the battery icon in the status bar is replaced
    by the charge as a number (for example 78%), shown in red at
    10% and below.
 
After installing, Radio Info shows the version as V1.0.38.P1.
 
BEFORE YOU INSTALL
  1. Make sure the radio is running the official TIDRADIO firmware
     V1.0.38 and APRS firmware V1.0.15 (check Radio Info).
  2. Fully charge the battery. Never flash on a low battery.
  3. Save your channels and settings with CHIRP or TIDRADIO's
     programming software.
  4. Keep a copy of the official TD-H9-V1.0.38.fw so you can go
     back to stock.
 
INSTALL
  1. Open the TIDRADIO Update App.
  2. Click OPEN and select TD-H9-V1.0.38.P1.fw.
  3. Check only the "Reset After Success" box.
  4. Plug the USB-C cable into the side of the radio.
  5. With the radio off, press and hold PTT and the 3 key while
     turning the radio on.
  6. The Update App shows the device info.
  7. Press UPDATE. Do not unplug the cable or turn off the radio
     until the update finishes.
  8. When the radio restarts, check that Radio Info shows
     V1.0.38.P1.
 
GOING BACK TO STOCK
  Follow the same install steps, but select the official
  TD-H9-V1.0.38.fw. Your channels and settings should stay; if
  not, restore them from your saved copy.
 
================================================================
  WARNING - UNOFFICIAL FIRMWARE
================================================================
 
THIS IS NOT OFFICIAL TIDRADIO FIRMWARE.
This firmware is a community modification of TIDRADIO's V1.0.38
firmware. It is not made, endorsed, tested or supported by
TIDRADIO. Do not contact TIDRADIO for help with it.
 
USE AT YOUR OWN RISK.
This firmware is provided "as is", with no warranty of any kind.
By flashing it you accept all risk. The authors and the people who
share it are not responsible for any damage, including:
  - a radio that will not start ("bricked") or must be recovered
  - lost channels, settings or memory contents
  - missed transmissions or traffic, wrong battery readings, or
    any other faulty behavior
  - any loss caused by relying on this radio
 
YOUR WARRANTY MAY BE VOID.
Installing unofficial firmware may void your TIDRADIO warranty, and
TIDRADIO may refuse to service a radio running it.
 
DO NOT RELY ON THIS RADIO FOR EMERGENCY OR LIFE-SAFETY
COMMUNICATIONS until you have tested it yourself, and keep a radio
with known-good firmware as a backup.
 
YOU ARE RESPONSIBLE FOR YOUR TRANSMISSIONS.
This firmware does not change frequencies, power or transmit
limits. The licensed operator is responsible for following the
rules for the bands they use.
 
DO NOT REPOST THE .fw FILE.
The .fw file is built from TIDRADIO's firmware. Get it only from
the person who gave it to you, and do not upload it to public sites.
 
If you do not accept these terms, do not install this firmware.
================================================================
