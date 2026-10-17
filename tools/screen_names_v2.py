"""Build and screen the names-v2 word lists (MeshLive Design G, review F23 and the two multilingual reviews of 2026-10-12).

names-v1 is frozen and kept (assets/names/*-v1.txt, vector history). names-v2 replaces every word flagged High or Medium by
EITHER review (the union of the two review files, kept outside the repository), the words
that break the near-shade rule, and screens the result with more lists and a pair blocklist.

The candidate words are original work (GPL-3.0-or-later with the app). The screening inputs are NOT shipped and are downloaded
by the operator: the LDNOOBW lists (CC BY 4.0) for en, es, fr, de, hi, ar, pt, tr, fa, ru, it, nl, and the CMU Pronouncing
Dictionary (BSD, pip package cmudict). LDNOOBW has no list for Bengali, Indonesian or Malay romanised, so MANUAL_BAD below is a
hand-written list of roots for them (a native reader still has to sign off: release-blocking).

Usage: python tools/screen_names_v2.py OUTDIR LDNOOBW_DIR CMUDICT_FILE V1_DIR
"""
import collections
import hashlib
import itertools
import os
import re
import sys

out, LDNOOBW_DIR, CMUDICT, V1_DIR = sys.argv[1:5]

# ----------------------------------------------------------------------------------------------- what changes from v1
# High or Medium in either review (union), plus the optional ones named by the coordinator.
REMOVE_REVIEW_ADJ = """shining desert airy sour frank satin dense broad balmy simple cream cherry woody copper salty shallow""".split()
REMOVE_REVIEW_NOUN = """cumin beaver pond swallow whale conch mound tetra clam deer snapper bunting trout llama sable""".split()
# The near-shade rule applied consistently (DECISIONS Design G F23): shades of the banned party/community colours (red, orange,
# saffron, green, black, white), skin-tone words and the "amber" alert cue are all out, not only the two ends.
REMOVE_SHADE_ADJ = """ivory chalk pearl onyx slate pewter cocoa coffee mocha caramel wheat honey almond beige ruby russet rose sienna coral amber rosy fawn bronze""".split()
REMOVE_ADJ = set(REMOVE_REVIEW_ADJ + REMOVE_SHADE_ADJ)
REMOVE_NOUN = set(REMOVE_REVIEW_NOUN)

ADJ_POOL = """
clever eager grand tidal rural urban wooden earthen spiral oval square boxy domed arched vaulted tiered layered nested
striped silver quince walnut carbon open radiant inland amused sharp folksy suede spacious temperate dappled stellar cosmic
mindful polished pastel silken hardy lively gentle quaint vibrant zesty spiffy wintry sheer lush floral fruity herbal piney adroit artsy astute blithe buoyant chic classy daring deft elated festive flowing glad humane idyllic
inspired lavish lilting lyrical mirthful modern natural poetic prized rapid robust sparky tuneful unique vital whole wondrous deluxe
""".split()
NOUN_POOL = """
bobcat sesame pool myna beluga krill bayou abalone bream eland waxwing dugong loach alpaca curlew ibex oryx grove alder oasis
vista dipper sunbird hoopoe macaw eel glacier cavern nutmeg papaya guava jasper marble granite basalt blossom gerbil grouper
grotto carrot radish pumpkin marina acacia anise arnica cassia cress currant fennec ginkgo gopher kestrel larch lobelia mallow
marmot mistral nettle petrel pipit quokka rabbit salvia sumac sundew lupine
""".split()
# words we looked at in the pools and refuse: slang or a meaning in a target language
POOL_REFUSE = set("""stacked lacy frilly slope smelt pollock catfish snow ginger lemon felt mica rattan""".split())

# ----------------------------------------------------------------------------------------------- the ban list (as v1)
BANNED = set("""
police army official admin help rescue safe free verified trusted urgent fire medic red orange saffron green black white
cow pig dog donkey monkey rat swine bull ox buffalo goat sheep camel snake cobra spider vulture hyena jackal crow owl
raven frog toad bat mouse roach cross crescent star dove lamb sage lotus devout holy sacred blessed angel devil demon god gods
storm thunder flood quake tsunami fear danger alarm warning fat dead death kill war gun bomb blood gay slave negro tribal
scarlet crimson maroon emerald lime olive jade mint apricot peach tawny mango ochre navy khaki dear lawful civil honest loyal
faithful trusty secure sure sound alert noble courtly gallant regal proud mature hidden stormy dim thick thin slim lanky burly
stout bent twisted wet laid ample checked candid dutiful true skunk possum lemming sloth hornet wasp locust harrier hawk tiger
lion eagle kiwi pigeon mole weasel seal shrimp spit badger corgi dingo coyote poppy jasmine meteor crab
amber slope stacked sponge
""".split()) | REMOVE_ADJ | REMOVE_NOUN | POOL_REFUSE

# 3-letter roots the v1 screen skipped (it only matched LDNOOBW entries of 4+ letters): a word may not EQUAL one, nor start (or
# end) with the longer ones, and a pair may not straddle one (review of 2026-10-12: "cumin" got through).
ROOT_WORDS = set("cum ass tit cu pus fap cok dik sex tai gud bal".split())
ROOT_PREFIX = ["cum", "ass", "tit", "cun", "fag"]
ROOT_SUFFIX = ["ass", "tit"]

# Hand-written romanised roots (no LDNOOBW list exists): Bengali, Hindi/Urdu romanised, Indonesian and Malay, Arabizi.
MANUAL_BAD = {
    "bn": "pond pondan khanki magi chod chuda chudi gud bal dhon gaand harami haram shala shuor kutta kukur boka".split(),
    "id_ms": "kontol memek pantat bangsat anjing babi lonte perek kimak puki butoh burit sundal bokep ngentot peler titit banci pondan jancok kampret".split(),
    "arabizi": "ayri ayre zeb zebi kos sharmouta kalb himar khara manyak gahba khawal teez".split(),
    "hinglish": "lund lauda bhosda randi tatti kutti phuddi chut chutiya madarchod behenchod bc mc jhaant muth".split(),
    "other": "slut whore bitch cunt dick cock pussy nigger fagot".split(),
}

# Famous or political phrases (pair level). A pair whose two words are both in the lists goes to blocked-pairs-v2.txt and is
# skipped at generation; the rest are recorded in the report so a later list cannot bring them back unnoticed.
FAMOUS_PAIRS = """
shining path
desert fox
desert lizard
desert storm
blue falcon
blue whale
blue film
golden dawn
gold dawn
gold rain
gold stream
golden shower
icy crystal
snowy crystal
silver fox
iron guard
iron fist
grey wolf
gray wolf
swift boat
cedar revolution
rose revolution
tulip revolution
jasmine revolution
velvet revolution
orange revolution
saffron revolution
black swan
red fox
lone wolf
polar bear
brave heart
""".strip().splitlines()
# Texture/body adjectives x shellfish and other nouns that can carry an innuendo in English.
CARRIER_ADJ = set("bushy furry fuzzy shaggy tufted fluffy downy fleecy plush damp tasty sweet round bouncy firm soft supple curved smooth warm deep large".split())
INNUENDO_NOUN = set("oyster mussel scallop clam conch beaver mound pond swallow jelly".split())


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[-1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


pron = {}
for line in open(CMUDICT, encoding="latin-1"):
    if line.startswith(";;;"):
        continue
    parts = line.split()
    w = re.sub(r"\(\d+\)$", "", parts[0])
    pron.setdefault(w, set()).add(" ".join(re.sub(r"\d", "", p) for p in parts[1:]))


def homophones(a, b):
    return bool(pron.get(a, set()) & pron.get(b, set()))


LANGS = ["en", "es", "fr", "de", "hi", "ar", "pt", "tr", "fa", "ru", "it", "nl"]
bad_words = {}
for lang in LANGS:
    bad_words[lang] = [l.strip().lower() for l in open(LDNOOBW_DIR + "/" + lang + ".txt", encoding="utf-8", errors="ignore") if l.strip()]
for lang, roots in MANUAL_BAD.items():
    bad_words["manual-" + lang] = roots
all_bad = sorted({e for v in bad_words.values() for e in v})
bad_single = {e for e in all_bad if " " not in e}
bad_long = [b for b in all_bad if " " not in b and len(b) >= 4]
bad_phrases = [b for b in all_bad if " " in b]


def word_problem(w):
    """Why [w] alone is refused, or None."""
    if not re.fullmatch(r"[a-z]{3,7}", w):
        return "shape"
    if w in BANNED:
        return "banned"
    if w in bad_single or w in ROOT_WORDS:
        return "bad word"
    if any(w.startswith(r) for r in ROOT_PREFIX) or any(w.endswith(r) for r in ROOT_SUFFIX):
        return "bad root at an end"
    if any(b in w for b in bad_long):
        return "bad substring"
    if w not in pron:
        return "no CMU entry"
    return None


def conflicts(w, others):
    for c in others:
        if c == w:
            return "duplicate"
        if c.startswith(w) or w.startswith(c):
            return "prefix of " + c
        if lev(c, w) < 2:
            return "edit distance with " + c
        if homophones(c, w):
            return "homophone of " + c
    return None


v1_adj = [w for w in open(V1_DIR + "/adjectives-v1.txt").read().split()]
v1_noun = [w for w in open(V1_DIR + "/nouns-v1.txt").read().split()]
report = []
adj = [w for w in v1_adj if w not in REMOVE_ADJ and word_problem(w) is None]
noun = [w for w in v1_noun if w not in REMOVE_NOUN and word_problem(w) is None]
for w in v1_adj:
    if w in REMOVE_ADJ:
        report.append("removed adjective %s (%s)" % (w, "review" if w in REMOVE_REVIEW_ADJ else "near-shade rule"))
    elif word_problem(w):
        report.append("removed adjective %s (%s)" % (w, word_problem(w)))
for w in v1_noun:
    if w in REMOVE_NOUN:
        report.append("removed noun %s (review)" % w)
    elif word_problem(w):
        report.append("removed noun %s (%s)" % (w, word_problem(w)))


def fill(words, pool, other, size=256):
    rejected = []
    for w in pool:
        if len(words) >= size:
            break
        problem = word_problem(w) or conflicts(w, words)
        if problem is None and (w in other or any(homophones(w, o) for o in other)):
            problem = "same or sounds like a word of the other list"
        if problem:
            rejected.append((w, problem))
        else:
            words.append(w)
    return rejected


rej_adj = fill(adj, ADJ_POOL, noun)
rej_noun = fill(noun, NOUN_POOL, adj)
spare_adj = [w for w in ADJ_POOL if w not in adj and word_problem(w) is None]
spare_noun = [w for w in NOUN_POOL if w not in noun and word_problem(w) is None]
print("sizes", len(adj), len(noun), "rejected", rej_adj, rej_noun)
if len(adj) < 256 or len(noun) < 256:
    sys.exit("pools too small: adj %d noun %d" % (len(adj), len(noun)))

# ---- pair screening: phrase / concatenation hits against every list, dropping the worst word and refilling
dropped = []
while True:
    cnt = collections.Counter()
    hits = []
    for a in adj:
        for n in noun:
            phrase, cat = a + " " + n, a + n
            for b in bad_phrases:
                if b == phrase:
                    hits.append((phrase, b, "phrase")); cnt[("a", a)] += 1; cnt[("n", n)] += 1
            for b in bad_long:
                if b in cat and b not in a and b not in n:
                    hits.append((phrase, b, "concat")); cnt[("a", a)] += 1; cnt[("n", n)] += 1
            for r in ROOT_PREFIX:
                if r in cat and r not in a and r not in n and len(r) >= 3:
                    hits.append((phrase, r, "root straddle")); cnt[("a", a)] += 1; cnt[("n", n)] += 1
    if not hits:
        break
    (kind, word), c = cnt.most_common(1)[0]
    dropped.append((kind, word, c))
    BANNED.add(word)
    if kind == "a":
        adj.remove(word)
        fill(adj, [w for w in spare_adj if w not in adj], noun)
    else:
        noun.remove(word)
        fill(noun, [w for w in spare_noun if w not in noun], adj)
    if len(adj) < 256 or len(noun) < 256:
        sys.exit("pools exhausted while pair screening; dropped so far: %s" % dropped)
adj, noun = sorted(adj), sorted(noun)
print("dropped for pair screening:", dropped)

# ---- final validation of every rule
for words in (adj, noun):
    assert len(words) == 256 and words == sorted(words)
    for w in words:
        assert word_problem(w) is None, (w, word_problem(w))
    for a, b in itertools.combinations(words, 2):
        assert lev(a, b) >= 2 and not a.startswith(b) and not b.startswith(a) and not homophones(a, b), (a, b)
assert not [(a, n) for a in adj for n in noun if a == n or homophones(a, n)]

# ---- the pair blocklist (enforced at generation): famous phrases and innuendo carriers whose two words are both listed
blocked = set()
dormant = []
for phrase in FAMOUS_PAIRS:
    a, n = phrase.split()
    if a in adj and n in noun:
        blocked.add(phrase)
    else:
        dormant.append(phrase)
for a in adj:
    for n in noun:
        if a in CARRIER_ADJ and n in INNUENDO_NOUN:
            blocked.add(a + " " + n)
blocked = sorted(blocked)

os.makedirs(out, exist_ok=True)
open(out + "/adjectives-v2.txt", "w", newline="\n").write("\n".join(adj) + "\n")
open(out + "/nouns-v2.txt", "w", newline="\n").write("\n".join(noun) + "\n")
open(out + "/blocked-pairs-v2.txt", "w", newline="\n").write("\n".join(blocked) + "\n")
hashes = {f: hashlib.sha256(open(out + "/" + f, "rb").read()).hexdigest() for f in ("adjectives-v2.txt", "nouns-v2.txt", "blocked-pairs-v2.txt")}
for f, h in hashes.items():
    print(f, h)
added_adj = [w for w in adj if w not in v1_adj]
added_noun = [w for w in noun if w not in v1_noun]
with open(out + "/names-v2-screening-report.txt", "w", newline="\n") as handle:
    handle.write("names-v2 screening report (Design G review F23 and the two multilingual reviews of 2026-10-12)\n")
    handle.write("Produced by tools/screen_names_v2.py. names-v1 stays frozen for vector history.\n\n")
    handle.write("Machine screen inputs (not shipped): LDNOOBW %s; CMU Pronouncing Dictionary; MANUAL_BAD roots for Bengali, Indonesian/Malay,\n" % ", ".join(LANGS))
    handle.write("romanised Hindi and Arabizi (written by hand in the script; LDNOOBW has no list for them); 3-letter roots (cum, ass, tit, cun,\n")
    handle.write("fag) as whole words, prefixes, suffixes and across the word boundary; %d famous or political phrases and an innuendo\n" % len(FAMOUS_PAIRS))
    handle.write("carrier x shellfish pattern as a PAIR BLOCKLIST enforced at generation.\n\n")
    handle.write("Rules for every word: a-z, 3-7 letters, sorted, unique, no prefix of another, edit distance >= 2 inside its list, no\n")
    handle.write("homophone inside or across the lists, not on the ban list (authority/emergency/trust cues, party and community colours AND\n")
    handle.write("their near shades, skin-tone words, insult or sacred animals, military/ethnic/national cues, the 'amber' alert cue).\n")
    handle.write("Near-shade rule, applied consistently in v2: ivory, chalk, pearl, onyx, slate, pewter, cocoa, coffee, mocha, caramel, wheat,\n")
    handle.write("honey, almond, beige, ruby, russet, rose, sienna, coral and amber left the list with ashen, sooty and the red/green/orange shades\n")
    handle.write("v1 had already removed. No exception remains.\n\n")
    handle.write("Frozen SHA-256:\n")
    for f, h in hashes.items():
        handle.write("  %-22s %s\n" % (f, h))
    handle.write("\nAdjectives added to v1: %s\nNouns added to v1: %s\n\n" % (" ".join(added_adj), " ".join(added_noun)))
    handle.write("Dropped during pair screening (word, hits): %s\n\n" % dropped)
    handle.write("Blocked pairs in force (%d): %s\n\n" % (len(blocked), "; ".join(blocked)))
    handle.write("Dormant phrases (a word is not in the lists; kept so a later list cannot bring them back): %s\n\n" % "; ".join(dormant))
    handle.write("OPEN, RELEASE-BLOCKING: the reading by speakers of Hindi, Arabic, Spanish and French (and, since the multilingual\n")
    handle.write("reviews, Portuguese, Bengali, Turkish, Indonesian/Malay) required by DECISIONS Design G F23 has NOT been done by a person.\n")
    handle.write("Two model reviews and the machine screen are not a substitute. Until a reviewer signs off, names-v2 must not ship.\n\n")
    handle.write("Removed and refused words:\n")
    for line in report:
        handle.write("  " + line + "\n")
    handle.write("Pool rejects (adjectives): %s\nPool rejects (nouns): %s\n" % (rej_adj, rej_noun))
