"""Screen the candidate word lists for names-v1 (MeshLive Design G, F23) and write the two list files and a report.

The candidates below were written for this project (original work, GPL-3.0-or-later with the app). The screening inputs are
NOT shipped and are downloaded by the operator: the LDNOOBW lists (CC BY 4.0, https://github.com/LDNOOBW) for en, es, fr,
de, hi, ar, and the CMU Pronouncing Dictionary (BSD, pip package cmudict, file cmudict.dict). The lists are frozen once
released; a changed list is names-v2 (never rerun this to replace names-v1).

Usage: python tools/screen_names_v1.py OUTDIR LDNOOBW_DIR CMUDICT_FILE
  LDNOOBW_DIR holds en.txt es.txt fr.txt de.txt hi.txt ar.txt
"""
import sys, os, re, hashlib, itertools

out = sys.argv[1]
LDNOOBW_DIR = sys.argv[2]
CMUDICT = sys.argv[3]

ADJ = """
blue teal gold silver amber ivory coral azure indigo violet lilac plum olive sandy cream jade mint rose ruby pearl copper
bronze iron steel stone slate cedar linen cotton silk wool glass clay chalk ashen dusty misty foggy windy sunny cloudy
rainy snowy frosty calm quiet gentle soft smooth warm cool mild kind brave bold bright clever quick swift steady patient
humble merry jolly lively happy cheerful polite modest tidy neat plain simple fresh crisp clear lucky noble proud wise
witty sleepy dreamy cozy snug sturdy nimble agile lithe spry hardy rustic golden silvery velvet satin woolly fuzzy round
tall tiny little small large broad narrow slim lean light airy hazy dewy mossy leafy woody rocky grassy flinty sunlit
moonlit starry solar lunar polar alpine coastal desert hidden distant nearby gleaming glowing shining sparkling shimmering
lofty tender mellow placid serene tranquil restful hushed muted faint pale dim deep shallow wide open sheltered
sheltered balmy breezy brisk chilly cold icy dry damp humid wet rainy stormy sandy earthy salty sweet sour spicy bitter
minty lemony nutty smoky toasty creamy buttery honeyed sugary velvety silken downy plush supple limber lanky stout
burly sleek shiny glossy matte rough coarse gritty dense thick thin flat curved bent twisted knotted braided woven
stitched sewn painted printed carved etched inked dotted striped checked plaid spotted speckled mottled dappled
tawny russet fawn beige taupe khaki ochre umber sienna maroon crimson scarlet cobalt navy sapphire emerald turquoise
aqua cyan magenta mauve lavender orchid peach apricot lemon lime mango cherry berry hazel chestnut walnut almond
cocoa mocha coffee vanilla ginger cinnamon pepper saffron
able active adept alert amiable ample artful avid awake blithe breezy candid careful casual cautious chipper civil
cordial courtly dapper decent dainty dashing dear devout dutiful eager earnest easy elegant even fair faithful fancy
fine firm fond frank gallant genial gifted gracious grand handy hearty honest hopeful hospitable ideal jaunty keen
laid lawful lively loyal lucid mannered mature measured mighty natty nice nifty orderly peppy perky pleasant poised
prime prompt prudent punctual ready refined regal rosy roomy sage sane savvy secure sensible settled shrewd skilled
snappy solid sound spirited stable stately sterling strong stylish suave sunlit sure tactful tenacious thrifty timely
trim true trusty upbeat upright valiant versed vivid zesty

pewter nickel zinc carbon hemp denim tweed felt burlap canvas paper wicker rattan cork wax onyx topaz mica flax oat wheat
barley honey maple caramel toffee butter grape apple quince raisin citrus tranquil genteel kindly friendly carefree relaxed
cheery chirpy bouncy bubbly frisky playful gleeful joyful jovial sprightly zippy plucky feisty dandy jazzy snazzy sporty
tasty crispy crunchy chewy fluffy bushy curly wavy shaggy freckled tufted feathered furry fleecy pebbly stony pearly
starlit sunbaked windswept lazy sunny breezy cuddly gleamy shimmery glittery twinkly rosy dusky sooty smoky silvery
tidal rural urban woolen leaden flaxen oaken wooden earthen golden brassy coppery steely stony mossy ferny lacy
frilly ruffled pleated folded rolled coiled looped curled spiral round oval ovate square boxy domed arched
vaulted tiered terraced layered nested stacked paired twin triple double single sole lone only first second third
fourth fifth sixth seventh eighth ninth tenth eager mellow sweeter
""".split()

NOUN = """
otter fox hare deer elk moose lynx puma panda koala lemur badger beaver marmot mole vole stoat ferret weasel seal whale
dolphin orca walrus turtle gecko newt toad finch wren robin swan heron crane stork egret ibis lark eagle falcon hawk kite
osprey parrot pigeon sparrow magpie plover pelican puffin tern gull quail trout salmon carp perch pike tuna manta ray crab
shrimp snail beetle cricket moth bee ant firefly river brook creek lake pond ocean bay cove reef island hill ridge peak
cliff canyon valley meadow field grove forest glade dune pebble boulder cobble crystal quartz opal garnet agate flint
cloud breeze rain mist frost snow dew comet meteor star moon aurora tide wave spring fern moss lily daisy tulip orchid
poppy clover willow maple birch aspen cedar pine oak elm ash acorn berry lemon mango peach pear plum olive fig cherry
bison yak llama alpaca gazelle impala zebra giraffe okapi tapir sloth armadillo anteater wombat wallaby kangaroo
possum raccoon skunk otter mink marten sable ocelot jaguar cheetah leopard tiger lion cougar caracal serval civet genet
mongoose meerkat lemming hamster gerbil squirrel chipmunk dormouse hedgehog porcupine pangolin aardvark bandicoot
numbat quokka dingo coyote fennec corgi pony mule burro bull ox stag buck fawn colt foal kid calf cub kit pup joey
heifer lamb ewe ram swallow martin thrush linnet siskin bunting warbler oriole tanager cardinal bluejay jay nuthatch
chickadee titmouse kestrel harrier merlin condor kiwi emu ostrich rhea cassowary penguin albatross petrel gannet
cormorant pelican booby frigate skua auk guillemot murre dunlin sandpiper curlew godwit snipe woodcock avocet
flamingo spoonbill bittern rail coot moorhen crake grebe loon teal mallard pintail wigeon eider goose gander
minnow guppy tetra betta koi goby wrasse snapper grouper marlin sailfish swordfish tarpon bonito mackerel sardine
anchovy herring haddock cod sole plaice flounder halibut turbot skate lobster oyster clam mussel scallop limpet
conch cowrie nautilus squid octopus cuttlefish starfish urchin anemone coral sponge jelly seahorse lizard iguana skink
anole chameleon tortoise terrapin lizard mantis cicada locust ladybug dragonfly damselfly mayfly lacewing weevil
hornet wasp aphid bumblebee sunflower marigold lavender jasmine violet thistle heather bramble bracken sedge reed
rush cattail lotus bamboo palm cypress spruce fir juniper hickory cherry hazel alder rowan linden sycamore beech
chestnut walnut pecan almond cashew pistachio ginger thyme sage basil mint fennel cumin clove sorrel parsley
meadow prairie savanna steppe tundra delta marsh bog fen swamp lagoon harbor inlet strait channel gulf shore beach
cape isle atoll shoal sandbar spit dune mesa butte plateau summit crest knoll hollow gorge ravine gully glen dell
vale basin crater geyser spring waterfall rapids cascade torrent stream rivulet trail path lane
sunrise sunset dawn dusk twilight horizon rainbow sunbeam moonbeam stardust nebula galaxy planet orbit eclipse

cactus agave sequoia bluebell buttercup foxglove primrose snowdrop crocus iris peony dahlia aster azalea camellia magnolia
lupine yarrow tansy fescue bluff fjord grotto gulch ledge mound oasis ravine slope tarn tor vista ibex oryx kudu eland nyala
topi gnu addax saiga chamois vicuna guanaco capybara agouti coati kinkajou margay tayra grison pipit dipper wagtail starling
bulbul sunbird barbet myna munia weaver lapwing shrike pitta drongo hornbill hoopoe lorikeet budgie cockatoo macaw toucan
pochard smew scoter shelduck gadwall garganey sturgeon tench bream chub rudd dace barbel char smelt shad gar tilapia
catfish loach pollock hake wahoo opah moray eel lamprey skimmer darner glacier cavern pinnacle reservoir geyser
nutmeg cardamom saffron clover quince damson medlar sloe elder hawthorn hazelnut tamarind papaya guava lychee kumquat
sapphire topaz beryl zircon jasper onyx amethyst citrine peridot tourmaline marble granite basalt gneiss shale
sandstone limestone gravel loam silt humus compost mulch petal blossom bud sprout seedling sapling timber driftwood
lantern kettle anchor compass lighthouse harbour pier jetty quay wharf buoy
""".split()

# Words that must never appear: authority/emergency/trust cues, political-religious-community colours, insulting animals,
# and the words whose reading is risky in target cultures (documented in DECISIONS Design G F23).
BANNED = set("""
easy fair pale burro mule prime orderly ready upright valiant mighty strong stately settled skilled versed sane timely prompt prudent poised refined nifty tactful stable solid casual ideal decent careful earnest
shrewd frisky perky thrifty genteel savvy nutty braided etched sewn knotted dotted frigate condor cougar galaxy dell
first second third fourth fifth sixth seventh eighth ninth tenth single double triple only sole lone twin paired sweeter sporty apple wax oat leaden ashen sooty tor gar dace rudd tench barbel shad gnu char medlar damson darner fescue humus vicuna guanaco moray compost silt loam gravel buoy hake kumquat lamprey zircon beryl peridot citrine tansy yarrow quay pier jetty wharf kettle anchor compass lantern timber bud petal sprout sapling
scarlet crimson maroon emerald lime olive jade mint apricot peach tawny mango ochre navy khaki dear lawful civil honest loyal
faithful trusty secure sure sound alert noble courtly gallant regal proud mature hidden stormy dim thick thin slim lanky burly
stout bent twisted wet laid ample checked candid dutiful true
skunk possum lemming sloth hornet wasp locust harrier hawk tiger lion eagle kiwi pigeon mole weasel seal shrimp spit badger corgi
dingo coyote poppy jasmine meteor crab

police army official admin help rescue safe free verified trusted urgent fire medic red orange saffron green black white
cow pig dog donkey monkey rat swine bull ox buffalo goat sheep camel snake cobra spider vulture hyena jackal crow owl
raven frog toad bat rat mouse roach
cross crescent star dove lamb sage lotus devout holy sacred blessed angel devil demon god gods
storm thunder flood quake tsunami fear danger alarm warning
fat dead death kill war gun bomb blood
gay slave negro tribal
""".split())

# A few extra cultural cautions found while screening: skip words that are food/sacred animals or slurs in target regions.
EXTRA_SKIP = set("""
kid colt foal calf cub kit pup joey heifer ram ewe stag buck gander coot bittern booby rail skate fen bog swamp
crack hoe nut nuts
""".split())


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[-1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


# CMU homophones
pron = {}
for line in open(CMUDICT, encoding="latin-1"):
    if line.startswith(";;;"):
        continue
    parts = line.split()
    w = re.sub(r"\(\d+\)$", "", parts[0])
    pron.setdefault(w, set()).add(" ".join(re.sub(r"\d", "", p) for p in parts[1:]))


def homophones(a, b):
    return bool(pron.get(a, set()) & pron.get(b, set()))


bad_words = {}
for lang in ["en", "es", "fr", "de", "hi", "ar"]:
    path = LDNOOBW_DIR + "/" + lang + ".txt"
    entries = [l.strip().lower() for l in open(path, encoding="utf-8", errors="ignore") if l.strip()]
    bad_words[lang] = entries
all_bad = sorted({e for v in bad_words.values() for e in v})
bad_single = {e for e in all_bad if " " not in e}


def pick(cands, exclude=()):
    chosen = []
    rejected = []
    seen = set()
    for w in cands:
        w = w.lower()
        if w in seen:
            continue
        seen.add(w)
        why = None
        if not re.fullmatch(r"[a-z]{3,7}", w):
            why = "shape"
        elif w in BANNED or w in EXTRA_SKIP:
            why = "banned"
        elif w in bad_single or any(w == b for b in all_bad):
            why = "ldnoobw word"
        elif any(len(b) >= 4 and b in w for b in all_bad if " " not in b):
            why = "ldnoobw substring"
        elif w not in pron:
            why = "not in CMU (unknown pronunciation)"
        elif any(w == e or homophones(w, e) for e in exclude):
            why = "same or sounds like a word in the other list"
        else:
            for c in chosen:
                if c.startswith(w) or w.startswith(c):
                    why = "prefix of " + c
                    break
                if lev(c, w) < 2:
                    why = "edit distance with " + c
                    break
                if homophones(c, w):
                    why = "homophone of " + c
                    break
        if why:
            rejected.append((w, why))
        else:
            chosen.append(w)
    return chosen, rejected


adj, adj_rej = pick(ADJ)
pass
noun, noun_rej = pick(NOUN, adj)
print("adjectives accepted", len(adj), "nouns accepted", len(noun))
if len(adj) < 256 or len(noun) < 256:
    print("NOT ENOUGH; rejected adj:", adj_rej[:60])
    print("rejected noun:", noun_rej[:60])
    sys.exit(1)


# Trim to 256 keeping a spread: prefer shorter, then alphabetical drop from the tail of the preferences by simple slice
def trim(ws):
    # prefer plainest: keep order of candidate list (curated by closeness to plain use), cut to 256
    return ws[:256]



import collections
multi = [b for b in all_bad if " " in b]
single_bad = [b for b in all_bad if " " not in b and len(b) >= 4]
adj_pool = list(adj)
noun_pool = list(noun)
adj = adj_pool[:256]
noun = noun_pool[:256]
dropped = []
while True:
    cnt = collections.Counter()
    hits = []
    for a in adj:
        for n in noun:
            phrase = a + " " + n
            cat = a + n
            for b in multi:
                if b == phrase:
                    hits.append((phrase, b, "phrase")); cnt[("a", a)] += 1; cnt[("n", n)] += 1
            for b in single_bad:
                if b in cat and b not in a and b not in n:
                    hits.append((phrase, b, "concat")); cnt[("a", a)] += 1; cnt[("n", n)] += 1
    if not hits:
        break
    (kind, word), _ = cnt.most_common(1)[0]
    dropped.append((kind, word, cnt[(kind, word)]))
    if kind == "a":
        adj.remove(word)
        spare = [w for w in adj_pool if w not in adj and w not in [d[1] for d in dropped]]
        adj.append(spare[0])
    else:
        noun.remove(word)
        spare = [w for w in noun_pool if w not in noun and w not in [d[1] for d in dropped]]
        noun.append(spare[0])
adj = sorted(adj); noun = sorted(noun)
print("dropped for pair screening:", dropped)
for ws in (adj, noun):
    for a, b in itertools.combinations(ws, 2):
        assert lev(a, b) >= 2 and not a.startswith(b) and not b.startswith(a) and not homophones(a, b), (a, b)
cross = [(a, n) for a in adj for n in noun if a == n or homophones(a, n)]
assert not cross, cross
assert len(adj) == 256 and len(noun) == 256
print("pair hits", len(hits))
os.makedirs(out, exist_ok=True)
open(out + "/adjectives-v1.txt", "w", newline="\n").write("\n".join(adj) + "\n")
open(out + "/nouns-v1.txt", "w", newline="\n").write("\n".join(noun) + "\n")
for f in ("adjectives-v1.txt", "nouns-v1.txt"):
    print(f, hashlib.sha256(open(out + "/" + f, "rb").read()).hexdigest())
open(out + "/names-v1-screening-report.txt", "w").write(
    "adjective rejects:\n" + "\n".join("%s %s" % r for r in adj_rej) + "\nnoun rejects:\n" + "\n".join("%s %s" % r for r in noun_rej)
    + "\npair hits:\n" + "\n".join(map(str, hits)) + "\n"
)
