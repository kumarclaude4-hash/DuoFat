package com.duoshield.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for the app's emoji catalogue, shared by
 * {@link EmojiKeyboardHelper} (message composer) and {@link ReactionPickerSheet}
 * (per-message reactions).
 *
 * <p><b>Why entries are one string, not parallel arrays.</b> Each entry is
 * {@code "<emoji> <keyword> <keyword> ..."} — the glyph, a single space, then search
 * keywords. No emoji sequence contains a space (ZWJ sequences use U+200D, variation
 * selectors are invisible modifiers), so splitting on the <i>first</i> space separates
 * glyph from keywords unambiguously. A parallel {@code String[] emoji} /
 * {@code String[] keywords} pair would carry the same information but silently
 * desynchronise the moment someone inserts into one array and not the other; here a
 * malformed entry is impossible to express.
 *
 * <p>Keywords are lowercase and space-separated, matched as prefixes so typing
 * {@code "lau"} finds {@code laugh}. The glyph itself is also matched, which is what
 * makes pasting an emoji into the search box work.
 */
public final class EmojiData {

    private EmojiData() {}

    /** Tab icons, one per category — parallel to {@link #CATEGORIES}. */
    public static final String[] CAT_ICONS = {
        "\uD83D\uDE00", "\uD83D\uDC4B", "\uD83D\uDC36", "\uD83C\uDF55",
        "\u26BD", "\u2708\uFE0F", "\uD83D\uDCA1", "\u2764\uFE0F"
    };

    /** Human-readable category names — parallel to {@link #CATEGORIES}. */
    public static final String[] CAT_NAMES = {
        "Smileys", "People", "Animals", "Food", "Activities", "Travel", "Objects", "Symbols"
    };

    /**
     * The catalogue: {@code CATEGORIES[category][index]} is one
     * {@code "<emoji> <keywords...>"} entry.
     */
    public static final String[][] CATEGORIES = {
        // ── Smileys & Emotion ───────────────────────────────────────────────
        {
            "😀 grin smile happy face",
            "😁 beam grin smile teeth happy",
            "😂 laugh lol tears joy funny cry",
            "🤣 rofl rolling laugh lol funny",
            "😃 smile happy open grin",
            "😄 smile happy grin eyes",
            "😅 sweat smile nervous relief phew",
            "😆 laugh squint grin haha",
            "😇 angel halo innocent",
            "😈 devil imp horns evil mischief",
            "😉 wink flirt",
            "😊 blush smile shy happy",
            "😋 yum tasty delicious tongue",
            "😌 relieved calm content",
            "😍 heart eyes love adore crush",
            "🥰 love hearts adore affection",
            "😎 cool sunglasses shades",
            "😏 smirk sly suggestive",
            "😐 neutral blank meh",
            "😑 expressionless blank unimpressed",
            "😒 unamused annoyed side eye",
            "😓 sweat sad downcast",
            "😔 sad pensive disappointed down",
            "😕 confused unsure slight frown",
            "😖 confounded frustrated",
            "😗 kiss kissing",
            "😘 kiss blow love heart",
            "😙 kiss smile",
            "😚 kiss closed eyes",
            "😛 tongue playful",
            "😜 wink tongue crazy silly",
            "😝 squint tongue silly",
            "😞 disappointed sad down",
            "😟 worried concerned anxious",
            "😠 angry mad annoyed",
            "😡 rage furious angry red mad",
            "😢 cry sad tear upset",
            "😣 persevere struggle",
            "😤 triumph huff steam angry proud",
            "😥 sad relieved disappointed",
            "😦 frown open surprised",
            "😧 anguished shocked",
            "😨 fearful scared afraid",
            "😩 weary tired frustrated",
            "😪 sleepy tired drowsy",
            "😫 tired exhausted weary",
            "😬 grimace awkward cringe",
            "😭 sob cry bawl loud sad",
            "😮 wow surprised shock open mouth gasp",
            "😯 hushed surprised quiet",
            "😰 anxious sweat nervous scared",
            "😱 scream shock fear horror",
            "😲 astonished shocked amazed",
            "😳 flushed embarrassed blush shy",
            "🥺 pleading puppy eyes beg cute",
            "😴 sleep zzz tired",
            "😵 dizzy knocked out faint",
            "🤐 zipper mouth secret quiet",
            "🤑 money mouth rich cash",
            "🤒 sick thermometer fever ill",
            "🤓 nerd geek glasses",
            "🤔 thinking hmm ponder wonder",
            "🤕 hurt injured bandage",
            "🤗 hug hugging thanks",
            "🤧 sneeze sick tissue",
            "🤨 raised eyebrow skeptical suspicious doubt",
            "🤩 star struck amazed wow excited",
            "🤪 zany goofy crazy wild",
            "🤫 shush quiet secret hush",
            "🤬 cursing swearing angry symbols",
            "🤭 oops giggle hand over mouth",
            "🥵 hot heat sweating overheated",
            "🥶 cold freezing frozen",
            "🥴 woozy drunk dizzy tipsy",
            "🥳 party celebrate birthday hooray",
            "🥸 disguise incognito glasses",
            "🤯 mind blown exploding head shock",
            "😶‍🌫️ fog clouds dazed lost",
            "🫠 melting hot dissolve",
            "🥹 holding back tears touched grateful",
            "😶 no mouth silent speechless",
            "💀 skull dead death rip",
            "☠️ skull crossbones death poison danger",
            "💩 poop crap shit",
            "🤡 clown joker circus",
            "👹 ogre monster demon",
            "👺 goblin monster angry",
            "👻 ghost boo halloween spooky",
            "👾 alien monster game invader",
            "🤖 robot bot ai machine",
            "😺 cat smile grin",
            "😸 cat grin happy",
            "😹 cat laugh tears joy",
            "😻 cat heart eyes love",
            "😼 cat smirk sly",
            "😽 cat kiss"
        },
        // ── People & Gestures ───────────────────────────────────────────────
        {
            "👋 wave hello hi bye hand",
            "🤚 raised back hand stop",
            "🖐️ hand fingers splayed five",
            "✋ hand stop high five raised",
            "🖖 vulcan spock salute star trek",
            "👌 ok perfect nice fingers",
            "🤌 pinched fingers italian gesture",
            "🤏 pinch small tiny little",
            "✌️ peace victory two fingers",
            "🤞 fingers crossed luck hope",
            "🤟 love you hand sign",
            "🤘 rock horns metal",
            "🤙 call me shaka hang loose",
            "👈 point left",
            "👉 point right",
            "👆 point up",
            "🖕 middle finger rude",
            "👇 point down",
            "☝️ point up index",
            "👍 thumbs up like yes ok good approve",
            "👎 thumbs down dislike no bad",
            "✊ fist raised power",
            "👊 fist bump punch",
            "🤛 fist left bump",
            "🤜 fist right bump",
            "🤝 handshake deal agree",
            "👏 clap applause bravo well done",
            "🙌 raised hands praise celebrate hooray",
            "👐 open hands hug",
            "🤲 palms up cupped pray",
            "🙏 pray thanks please namaste grateful",
            "✍️ write writing hand pen",
            "💅 nail polish manicure",
            "🤳 selfie phone",
            "💪 muscle flex strong biceps",
            "🦵 leg kick",
            "🦶 foot kick",
            "👂 ear listen hear",
            "🦻 hearing aid ear",
            "👃 nose smell",
            "🧠 brain smart mind think",
            "🦷 tooth dentist",
            "🦴 bone",
            "👀 eyes look watch see",
            "👁️ eye look see",
            "👅 tongue taste lick",
            "👄 mouth lips",
            "🫦 biting lip nervous",
            "💋 kiss lips lipstick",
            "🧑 person adult",
            "👦 boy child",
            "👧 girl child",
            "👨 man male",
            "👩 woman female",
            "👴 old man grandpa elderly",
            "👵 old woman grandma elderly",
            "🧒 child kid",
            "👶 baby infant",
            "🧑‍🤝‍🧑 people holding hands friends",
            "💑 couple love heart",
            "👨‍👩‍👧 family daughter",
            "👨‍👩‍👦 family son",
            "🧑‍💼 office worker business",
            "🧑‍🎤 singer artist musician"
        },
        // ── Animals & Nature ────────────────────────────────────────────────
        {
            "🐶 dog puppy pet",
            "🐱 cat kitten pet",
            "🐭 mouse",
            "🐹 hamster pet",
            "🐰 rabbit bunny",
            "🦊 fox",
            "🐻 bear",
            "🐼 panda",
            "🐨 koala",
            "🐯 tiger",
            "🦁 lion",
            "🐮 cow",
            "🐷 pig",
            "🐸 frog",
            "🐵 monkey",
            "🙈 see no evil monkey shy",
            "🙉 hear no evil monkey",
            "🙊 speak no evil monkey quiet",
            "🐔 chicken hen",
            "🐧 penguin",
            "🐦 bird",
            "🐤 chick baby bird",
            "🦆 duck",
            "🦅 eagle",
            "🦉 owl",
            "🦇 bat",
            "🐺 wolf",
            "🐗 boar pig",
            "🐴 horse",
            "🦄 unicorn magic",
            "🐝 bee honey",
            "🐛 bug caterpillar",
            "🦋 butterfly",
            "🐌 snail slow",
            "🐞 ladybug beetle",
            "🐜 ant",
            "🦗 cricket grasshopper",
            "🕷️ spider",
            "🦂 scorpion",
            "🐢 turtle tortoise slow",
            "🦎 lizard gecko",
            "🐍 snake",
            "🐲 dragon",
            "🦕 dinosaur brontosaurus",
            "🦖 t rex dinosaur",
            "🦈 shark",
            "🐬 dolphin",
            "🐳 whale",
            "🐋 whale",
            "🦭 seal",
            "🦞 lobster",
            "🦀 crab",
            "🦑 squid",
            "🐙 octopus",
            "🦐 shrimp",
            "🐡 blowfish puffer",
            "🌸 blossom flower cherry spring",
            "🌺 hibiscus flower",
            "🌻 sunflower",
            "🌹 rose flower love",
            "🌷 tulip flower",
            "🌿 herb leaves plant",
            "🌱 seedling sprout plant grow",
            "🍀 four leaf clover luck"
        },
        // ── Food & Drink ────────────────────────────────────────────────────
        {
            "🍕 pizza slice",
            "🍔 burger hamburger",
            "🌮 taco",
            "🌯 burrito wrap",
            "🥙 pita stuffed flatbread",
            "🧆 falafel",
            "🥚 egg",
            "🍳 fried egg cooking breakfast",
            "🥞 pancakes breakfast",
            "🧇 waffle breakfast",
            "🥓 bacon",
            "🍗 chicken drumstick poultry",
            "🍖 meat bone",
            "🌭 hot dog",
            "🍟 fries chips",
            "🍿 popcorn movie",
            "🧂 salt",
            "🧈 butter",
            "🥗 salad healthy greens",
            "🍱 bento lunch box",
            "🍣 sushi",
            "🍜 ramen noodles soup",
            "🍝 spaghetti pasta",
            "🍛 curry rice",
            "🍲 stew pot soup",
            "🍥 fish cake",
            "🥮 moon cake",
            "🍡 dango sweet",
            "🧁 cupcake",
            "🎂 birthday cake celebrate",
            "🍰 cake slice dessert",
            "🍮 custard pudding flan",
            "🍭 lollipop candy",
            "🍬 candy sweet",
            "🍫 chocolate bar",
            "🍩 doughnut donut",
            "🍪 cookie biscuit",
            "🌰 chestnut nut",
            "🥜 peanuts nuts",
            "🍯 honey pot",
            "🍎 apple fruit red",
            "🍐 pear fruit",
            "🍊 orange tangerine fruit",
            "🍋 lemon sour fruit",
            "🍌 banana fruit",
            "🍉 watermelon fruit",
            "🍇 grapes fruit",
            "🍓 strawberry fruit",
            "🫐 blueberries fruit",
            "🍈 melon fruit",
            "🍑 peach fruit butt",
            "🥭 mango fruit",
            "🍍 pineapple fruit",
            "🥥 coconut",
            "🥝 kiwi fruit",
            "🍅 tomato",
            "🥤 cup straw soda drink",
            "☕ coffee tea hot drink",
            "🍵 green tea matcha",
            "🧃 juice box drink",
            "🍺 beer mug drink cheers",
            "🍻 beers cheers clink drink",
            "🥂 champagne cheers toast celebrate",
            "🍷 wine glass drink"
        },
        // ── Activities & Sport ──────────────────────────────────────────────
        {
            "⚽ soccer football ball",
            "🏀 basketball ball",
            "🏈 american football ball",
            "⚾ baseball ball",
            "🥎 softball ball",
            "🏐 volleyball ball",
            "🏉 rugby ball",
            "🎾 tennis ball",
            "🥏 frisbee disc",
            "🎱 pool billiards eight ball",
            "🏓 ping pong table tennis",
            "🏸 badminton",
            "🏒 ice hockey",
            "🏑 field hockey",
            "🥍 lacrosse",
            "🏏 cricket bat",
            "⛳ golf flag hole",
            "🪃 boomerang",
            "🥊 boxing glove fight",
            "🥋 martial arts karate judo",
            "🎽 running shirt sash",
            "🛹 skateboard",
            "🛼 roller skate",
            "🛷 sled sledge",
            "🏂 snowboard",
            "🪂 parachute skydive",
            "🤸 cartwheel gymnastics",
            "⛷️ ski skiing",
            "🏋️ weightlifting gym lifting",
            "🤼 wrestling",
            "🤺 fencing sword",
            "🏇 horse racing jockey",
            "🤾 handball",
            "🏌️ golfing golf",
            "🏄 surfing surf wave",
            "🤽 water polo",
            "🚣 rowing boat",
            "🧘 yoga meditate lotus calm",
            "🏊 swimming swim",
            "🤿 diving mask scuba",
            "🎯 target bullseye darts aim",
            "🎳 bowling",
            "🎮 video game controller gaming",
            "🎰 slot machine gamble",
            "🧩 puzzle piece jigsaw",
            "🎲 dice game luck",
            "♟️ chess pawn strategy",
            "🎭 theatre masks drama",
            "🎨 art palette paint",
            "🖼️ framed picture art",
            "🎼 musical score sheet music",
            "🎤 microphone sing karaoke",
            "🎧 headphones music listen",
            "🎷 saxophone jazz",
            "🎺 trumpet",
            "🎸 guitar rock music",
            "🪕 banjo",
            "🎻 violin",
            "🥁 drum music",
            "🪘 long drum",
            "🎬 clapper board film movie action",
            "🎥 movie camera film",
            "📷 camera photo",
            "📸 camera flash photo"
        },
        // ── Travel & Places ────────────────────────────────────────────────
        {
            "✈️ airplane flight travel plane",
            "🚀 rocket launch space",
            "🛸 flying saucer ufo alien",
            "🚁 helicopter",
            "🛩️ small airplane",
            "🚂 locomotive train steam",
            "🚃 railway car train",
            "🚄 bullet train",
            "🚅 bullet train fast",
            "🚆 train",
            "🚇 metro subway underground",
            "🚈 light rail",
            "🚉 station train",
            "🚊 tram",
            "🚝 monorail",
            "🚞 mountain railway",
            "🚋 tram car",
            "🚌 bus",
            "🚍 oncoming bus",
            "🚎 trolleybus",
            "🏎️ racing car race fast",
            "🚐 minibus van",
            "🚑 ambulance emergency",
            "🚒 fire engine truck",
            "🚓 police car",
            "🚔 police car oncoming",
            "🚕 taxi cab",
            "🚖 taxi oncoming",
            "🚗 car automobile drive",
            "🚘 car oncoming",
            "🛻 pickup truck",
            "🚙 suv jeep car",
            "🛵 scooter moped",
            "🏍️ motorcycle bike",
            "🚲 bicycle bike cycle",
            "🛴 kick scooter",
            "🛺 auto rickshaw tuktuk",
            "🚏 bus stop",
            "🛣️ motorway highway road",
            "🗺️ world map travel",
            "🌍 earth globe europe africa world",
            "🌎 earth globe americas world",
            "🌏 earth globe asia australia world",
            "🌐 globe meridians internet web",
            "🗾 japan map",
            "🧭 compass navigate direction",
            "🏔️ mountain snow peak",
            "⛰️ mountain",
            "🌋 volcano eruption",
            "🗻 mount fuji",
            "🏕️ camping tent",
            "🏖️ beach umbrella holiday",
            "🏜️ desert cactus",
            "🏝️ desert island tropical",
            "🏞️ national park nature",
            "🏟️ stadium arena",
            "🏛️ classical building museum",
            "🗼 tokyo tower",
            "🗽 statue of liberty new york",
            "🗿 moai statue easter island",
            "🏰 castle",
            "🏯 japanese castle",
            "🌁 foggy bridge",
            "🌃 night stars city"
        },
        // ── Objects ─────────────────────────────────────────────────────────
        {
            "💡 light bulb idea",
            "🔦 flashlight torch",
            "🕯️ candle",
            "🪔 diya lamp oil",
            "💰 money bag cash rich",
            "💵 dollar money cash banknote",
            "💴 yen money banknote",
            "💶 euro money banknote",
            "💷 pound money banknote",
            "💸 money wings flying spend",
            "💳 credit card payment",
            "💎 gem diamond jewel",
            "⚖️ scales balance justice law",
            "🧲 magnet attract",
            "🔧 wrench spanner tool fix",
            "🔨 hammer tool build",
            "⚒️ hammer pick tools",
            "🛠️ hammer wrench tools settings",
            "⛏️ pick mining",
            "🔩 nut bolt hardware",
            "🪛 screwdriver tool",
            "🪚 saw carpentry tool",
            "🔗 link chain url",
            "⛓️ chains links",
            "🪝 hook",
            "🧰 toolbox tools",
            "🔑 key unlock access",
            "🗝️ old key",
            "🔐 locked with key secure",
            "🔒 lock locked secure private",
            "🔓 unlock open unlocked",
            "📱 mobile phone cell smartphone",
            "💻 laptop computer",
            "🖥️ desktop computer monitor",
            "🖨️ printer print",
            "⌨️ keyboard typing",
            "🖱️ computer mouse",
            "🖲️ trackball",
            "💾 floppy disk save",
            "💿 cd disc",
            "📀 dvd disc",
            "📼 videocassette vhs tape",
            "📷 camera photo picture",
            "📸 camera flash photo",
            "📹 video camera record",
            "🎥 movie camera film",
            "📞 telephone receiver call phone",
            "📟 pager beeper",
            "📠 fax machine",
            "📺 television tv screen",
            "📻 radio",
            "🧭 compass direction",
            "⏰ alarm clock time wake",
            "⏱️ stopwatch timer",
            "⏲️ timer clock",
            "📦 package box parcel shipping",
            "📫 mailbox mail post",
            "📪 mailbox closed",
            "📬 mailbox mail incoming",
            "📭 mailbox empty",
            "📮 postbox mail",
            "🗳️ ballot box vote",
            "✏️ pencil write edit"
        },
        // ── Symbols & Hearts ───────────────────────────────────────────────
        {
            "❤️ red heart love like",
            "🧡 orange heart love",
            "💛 yellow heart love friendship",
            "💚 green heart love",
            "💙 blue heart love",
            "💜 purple heart love",
            "🖤 black heart love dark",
            "🤍 white heart love",
            "🤎 brown heart love",
            "💔 broken heart heartbreak sad",
            "❣️ heart exclamation love",
            "💕 two hearts love",
            "💞 revolving hearts love",
            "💓 beating heart love pulse",
            "💗 growing heart love",
            "💖 sparkling heart love",
            "💘 heart arrow cupid love",
            "💝 heart ribbon gift love",
            "💟 heart decoration love",
            "☮️ peace symbol",
            "✝️ latin cross christian",
            "☪️ star crescent islam",
            "🕉️ om hindu",
            "☯️ yin yang balance",
            "✡️ star of david judaism",
            "🔯 six pointed star",
            "🕎 menorah hanukkah",
            "☦️ orthodox cross",
            "⛎ ophiuchus zodiac",
            "♈ aries zodiac ram",
            "♉ taurus zodiac bull",
            "♊ gemini zodiac twins",
            "♋ cancer zodiac crab",
            "♌ leo zodiac lion",
            "♍ virgo zodiac",
            "♎ libra zodiac scales",
            "♏ scorpio zodiac scorpion",
            "♐ sagittarius zodiac archer",
            "♑ capricorn zodiac goat",
            "♒ aquarius zodiac water",
            "♓ pisces zodiac fish",
            "🆔 id identification",
            "🆕 new",
            "🆖 ng no good",
            "🆗 ok okay",
            "🆘 sos help emergency",
            "🆙 up level",
            "🆚 vs versus",
            "✅ check tick done yes correct",
            "❎ cross no wrong",
            "🔴 red circle dot record",
            "🟠 orange circle dot",
            "🟡 yellow circle dot",
            "🟢 green circle dot online",
            "🔵 blue circle dot",
            "🟣 purple circle dot",
            "🎉 party popper celebrate congrats hooray",
            "🎊 confetti ball celebrate party",
            "🔥 fire hot lit flame",
            "⭐ star favourite",
            "🌟 glowing star sparkle",
            "✨ sparkles shine magic",
            "💯 hundred perfect score",
            "❗ exclamation important",
            "❓ question mark",
            "⚠️ warning caution alert"
        }
    };

    /** One searchable emoji plus the keywords that match it. */
    public static final class Entry {
        public final String emoji;
        public final String keywords;   // lowercase, space-separated
        public final int    category;

        Entry(String emoji, String keywords, int category) {
            this.emoji    = emoji;
            this.keywords = keywords;
            this.category = category;
        }
    }

    private static List<Entry> flatCache;

    /** Every entry across every category, in catalogue order. */
    public static synchronized List<Entry> all() {
        if (flatCache != null) return flatCache;
        List<Entry> out = new ArrayList<>();
        for (int cat = 0; cat < CATEGORIES.length; cat++) {
            for (String raw : CATEGORIES[cat]) {
                int sp = raw.indexOf(' ');
                if (sp <= 0) {
                    // No keywords supplied — still searchable by its own glyph.
                    out.add(new Entry(raw, "", cat));
                } else {
                    out.add(new Entry(raw.substring(0, sp),
                                      raw.substring(sp + 1).toLowerCase(Locale.ROOT),
                                      cat));
                }
            }
        }
        flatCache = out;
        return out;
    }

    /** The bare glyphs of one category, for grid rendering without search. */
    public static String[] emojisIn(int category) {
        String[] raw = CATEGORIES[category];
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int sp = raw[i].indexOf(' ');
            out[i] = sp <= 0 ? raw[i] : raw[i].substring(0, sp);
        }
        return out;
    }

    /**
     * Emoji whose keywords prefix-match every whitespace-separated token in
     * {@code query}, across all categories. An empty query returns an empty list —
     * callers show the category grid in that case rather than dumping all 550 glyphs
     * into a flat list.
     *
     * <p>Prefix rather than substring matching so {@code "an"} surfaces {@code angry}
     * and {@code animal} but not every emoji containing "an" mid-word (e.g. {@code plane},
     * {@code banana}), which buries the intended hit.
     */
    public static List<String> search(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return out;

        String[] tokens = q.split("\\s+");
        for (Entry e : all()) {
            if (e.emoji.contains(q)) { out.add(e.emoji); continue; }
            boolean allMatch = true;
            for (String token : tokens) {
                if (!hasPrefixWord(e.keywords, token)) { allMatch = false; break; }
            }
            if (allMatch) out.add(e.emoji);
        }
        return out;
    }

    /** True when any space-separated word in {@code keywords} starts with {@code prefix}. */
    private static boolean hasPrefixWord(String keywords, String prefix) {
        if (keywords.isEmpty()) return false;
        int from = 0;
        while (from <= keywords.length()) {
            int end = keywords.indexOf(' ', from);
            if (end < 0) end = keywords.length();
            if (end - from >= prefix.length()
                    && keywords.startsWith(prefix, from)) {
                return true;
            }
            from = end + 1;
        }
        return false;
    }
}
