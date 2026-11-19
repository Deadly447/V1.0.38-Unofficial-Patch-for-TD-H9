package net.kg7lbj.h9monitor.aprs;

import java.util.HashMap;
import java.util.Map;

/** Plain-English names for the common APRS symbols. */
public final class Symbols {
    private Symbols() {}

    private static final Map<String, String> NAMES = new HashMap<>();

    private static void p(char c, String n) { NAMES.put("/" + c, n); }
    private static void a(char c, String n) { NAMES.put("\\" + c, n); }

    static {
        p('!', "Police"); p('#', "Digipeater"); p('$', "Phone"); p('%', "DX cluster");
        p('&', "HF gateway"); p('\'', "Small aircraft"); p('(', "Mobile satellite station");
        p('*', "Snowmobile"); p('+', "Red Cross"); p(',', "Boy Scouts"); p('-', "House");
        p('.', "X"); p('/', "Dot"); p(':', "Fire"); p(';', "Campground"); p('<', "Motorcycle");
        p('=', "Railroad engine"); p('>', "Car"); p('?', "Server"); p('@', "Hurricane");
        p('A', "Aid station"); p('B', "BBS"); p('C', "Canoe"); p('E', "Eyeball"); p('F', "Tractor");
        p('G', "Grid square"); p('H', "Hotel"); p('I', "TCP/IP"); p('K', "School");
        p('L', "PC user"); p('M', "Mac"); p('N', "NTS station"); p('O', "Balloon");
        p('P', "Police"); p('R', "RV"); p('S', "Space shuttle"); p('T', "SSTV"); p('U', "Bus");
        p('V', "ATV"); p('W', "Weather service site"); p('X', "Helicopter"); p('Y', "Sailboat");
        p('Z', "Windows user"); p('[', "Person"); p('\\', "Triangle"); p(']', "Mailbox");
        p('^', "Large aircraft"); p('_', "Weather station"); p('`', "Dish antenna");
        p('a', "Ambulance"); p('b', "Bicycle"); p('c', "Incident command post"); p('d', "Fire department");
        p('e', "Horse"); p('f', "Fire truck"); p('g', "Glider"); p('h', "Hospital");
        p('i', "IOTA"); p('j', "Jeep"); p('k', "Truck"); p('l', "Laptop"); p('m', "Mic-E repeater");
        p('n', "Node"); p('o', "EOC"); p('p', "Dog"); p('q', "Grid square");
        p('r', "Repeater"); p('s', "Boat"); p('t', "Truck stop"); p('u', "Semi truck"); p('v', "Van");
        p('w', "Water station"); p('x', "xAPRS"); p('y', "Yagi at QTH"); p('z', "Shelter");
        a('#', "Digipeater"); a('&', "Gateway"); a('\'', "Crash site"); a('-', "House (HF)");
        a('>', "Car"); a('A', "Box"); a('a', "ARES/RACES"); a('D', "Depot");
        a('E', "Smoke"); a('F', "Freezing rain"); a('H', "Haze"); a('I', "Rain shower");
        a('K', "Kenwood"); a('L', "Lighthouse"); a('N', "Buoy"); a('O', "Rocket");
        a('P', "Parking"); a('Q', "Earthquake"); a('R', "Restaurant"); a('S', "Satellite");
        a('T', "Thunderstorm"); a('U', "Sunny"); a('V', "VORTAC"); a('W', "NWS site");
        a('X', "Pharmacy"); a('[', "Wall cloud"); a('^', "Aircraft"); a('_', "Weather site");
        a('a', "ARES"); a('c', "Emergency/civil defense"); a('h', "Ham store"); a('j', "Work zone");
        a('k', "SUV"); a('m', "Milepost"); a('n', "Triangle"); a('o', "Small circle");
        a('r', "Restrooms"); a('s', "Ship"); a('t', "Tornado"); a('u', "Truck");
        a('v', "Van"); a('w', "Flooding"); a('y', "Skywarn"); a('z', "Shelter");
    }

    public static String name(char table, char code) {
        boolean overlay = table != '/' && table != '\\';
        String n = NAMES.get((overlay ? "\\" : String.valueOf(table)) + code);
        if (n == null) n = "Symbol " + table + code;
        if (overlay) n = n + " (" + table + ")";
        return n;
    }

    /** One short emoji for the list view. */
    public static String icon(char table, char code) {
        switch (code) {
            case '>': case 'j': case 'k': case 'u': case 'v': case 'R': case 'U': return "🚗";
            case '<': return "🏍";
            case 'b': return "🚲";
            case '[': case 'e': return "🚶";
            case '-': case 'y': return "🏠";
            case '_': return "🌤";
            case '#': return "📡";
            case '&': return "📶";
            case 'r': case 'm': return "📻";
            case '^': case '\'': case 'X': case 'g': return "✈";
            case 'O': return "🎈";
            case 's': case 'Y': case 'C': return "⛵";
            case 'a': case 'h': case '+': return "🚑";
            case 'f': case 'd': case ':': return "🚒";
            case '!': case 'P': return "🚓";
            case 'o': case 'c': return "🏛";
            case ';': return "⛺";
            default: return "📍";
        }
    }
}
