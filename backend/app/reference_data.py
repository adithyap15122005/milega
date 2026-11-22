"""Bengaluru reference data: curated localities and category → Google place-type mapping.

Locality coordinates are *approximate neighbourhood centres* used to set a search
centre when a customer picks an area. They are not boundaries. Areas missing from
this list are found through Google Places Autocomplete (when configured).
"""

from __future__ import annotations

# Bengaluru urban area, used to restrict autocomplete and validate coordinates.
BENGALURU_BOUNDS = {"low": (12.73, 77.35), "high": (13.25, 77.86)}
BENGALURU_CENTER = (12.9716, 77.5946)

# slug, English name, Kannada name, lat, lng, typical radius (m), extra aliases
LOCALITIES: list[tuple[str, str, str | None, float, float, int, list[str]]] = [
    ("jayanagar", "Jayanagar", "ಜಯನಗರ", 12.9299, 77.5838, 2500, ["jaynagar", "जयनगर"]),
    ("jp-nagar", "JP Nagar", "ಜೆಪಿ ನಗರ", 12.9063, 77.5857, 3000, ["j p nagar", "jpnagar", "jayaprakash nagar", "जेपी नगर"]),
    ("basavanagudi", "Basavanagudi", "ಬಸವನಗುಡಿ", 12.9422, 77.5737, 2000, ["basavangudi", "बसवनगुडी"]),
    ("banashankari", "Banashankari", "ಬನಶಂಕರಿ", 12.9255, 77.5468, 3000, ["bsk", "बनशंकरी"]),
    ("btm-layout", "BTM Layout", "ಬಿಟಿಎಂ ಲೇಔಟ್", 12.9166, 77.6101, 2500, ["btm", "बीटीएम"]),
    ("koramangala", "Koramangala", "ಕೋರಮಂಗಲ", 12.9352, 77.6245, 2500, ["kormangala", "कोरमंगला"]),
    ("hsr-layout", "HSR Layout", "ಎಚ್‌ಎಸ್‌ಆರ್ ಲೇಔಟ್", 12.9121, 77.6446, 2500, ["hsr", "एचएसआर"]),
    ("indiranagar", "Indiranagar", "ಇಂದಿರಾನಗರ", 12.9719, 77.6412, 2500, ["indira nagar", "इंदिरानगर"]),
    ("whitefield", "Whitefield", "ವೈಟ್‌ಫೀಲ್ಡ್", 12.9698, 77.7500, 4000, ["white field", "व्हाइटफील्ड"]),
    ("marathahalli", "Marathahalli", "ಮಾರತಹಳ್ಳಿ", 12.9569, 77.7011, 2500, ["marthahalli", "मराठाहल्ली"]),
    ("bellandur", "Bellandur", "ಬೆಳ್ಳಂದೂರು", 12.9304, 77.6784, 2500, ["belandur"]),
    ("sarjapur-road", "Sarjapur Road", "ಸರ್ಜಾಪುರ ರಸ್ತೆ", 12.9100, 77.6870, 3500, ["sarjapura road", "sarjapur"]),
    ("electronic-city", "Electronic City", "ಎಲೆಕ್ಟ್ರಾನಿಕ್ ಸಿಟಿ", 12.8452, 77.6602, 4000, ["e city", "ecity", "electronics city"]),
    ("malleshwaram", "Malleshwaram", "ಮಲ್ಲೇಶ್ವರಂ", 13.0031, 77.5643, 2000, ["malleswaram", "malleshwaram", "मल्लेश्वरम"]),
    ("rajajinagar", "Rajajinagar", "ರಾಜಾಜಿನಗರ", 12.9910, 77.5525, 2500, ["rajaji nagar", "राजाजीनगर"]),
    ("vijayanagar", "Vijayanagar", "ವಿಜಯನಗರ", 12.9719, 77.5333, 2500, ["vijay nagar", "विजयनगर"]),
    ("yelahanka", "Yelahanka", "ಯಲಹಂಕ", 13.1007, 77.5963, 4000, ["yelhanka", "येलहंका"]),
    ("hebbal", "Hebbal", "ಹೆಬ್ಬಾಳ", 13.0358, 77.5970, 2500, ["hebbala", "हेब्बल"]),
    ("rt-nagar", "RT Nagar", "ಆರ್‌ಟಿ ನಗರ", 13.0213, 77.5946, 2000, ["r t nagar", "rtnagar"]),
    ("kr-puram", "KR Puram", "ಕೆಆರ್ ಪುರಂ", 13.0076, 77.6950, 3000, ["k r puram", "krishnarajapuram"]),
    ("kengeri", "Kengeri", "ಕೆಂಗೇರಿ", 12.9081, 77.4826, 3500, ["kengeri satellite town"]),
    ("nagarbhavi", "Nagarbhavi", "ನಾಗರಭಾವಿ", 12.9601, 77.5097, 3000, ["nagarabhavi"]),
    ("peenya", "Peenya", "ಪೀಣ್ಯ", 13.0285, 77.5197, 3500, ["peenya industrial area"]),
    ("hennur", "Hennur", "ಹೆಣ್ಣೂರು", 13.0358, 77.6431, 2500, ["hennur road"]),
    ("kammanahalli", "Kammanahalli", "ಕಮ್ಮನಹಳ್ಳಿ", 13.0157, 77.6378, 2000, ["kamanahalli"]),
    ("shivajinagar", "Shivajinagar", "ಶಿವಾಜಿನಗರ", 12.9857, 77.6057, 1500, ["shivaji nagar"]),
    ("frazer-town", "Frazer Town", "ಫ್ರೇಜರ್ ಟೌನ್", 12.9984, 77.6153, 1500, ["fraser town", "pulikeshi nagar"]),
    ("chickpet", "Chickpet", "ಚಿಕ್ಕಪೇಟೆ", 12.9698, 77.5779, 1200, ["chikpet", "chikkapete"]),
    ("commercial-street", "Commercial Street", "ಕಮರ್ಷಿಯಲ್ ಸ್ಟ್ರೀಟ್", 12.9822, 77.6083, 800, ["commercial st"]),
    ("mg-road", "MG Road", "ಎಂಜಿ ರಸ್ತೆ", 12.9756, 77.6067, 1200, ["m g road", "mahatma gandhi road"]),
    ("ulsoor", "Ulsoor", "ಹಲಸೂರು", 12.9817, 77.6286, 1500, ["halasuru", "halsoor"]),
    ("majestic", "Majestic", "ಮೆಜೆಸ್ಟಿಕ್", 12.9767, 77.5713, 1500, ["gandhinagar", "kempegowda bus station"]),
    ("chamrajpet", "Chamrajpet", "ಚಾಮರಾಜಪೇಟೆ", 12.9600, 77.5650, 1500, ["chamarajpet"]),
    ("sadashivanagar", "Sadashivanagar", "ಸದಾಶಿವನಗರ", 13.0068, 77.5813, 1500, []),
    ("seshadripuram", "Seshadripuram", "ಶೇಷಾದ್ರಿಪುರಂ", 12.9934, 77.5757, 1200, []),
    ("richmond-town", "Richmond Town", "ರಿಚ್ಮಂಡ್ ಟೌನ್", 12.9636, 77.6012, 1200, []),
    ("shanti-nagar", "Shanti Nagar", "ಶಾಂತಿನಗರ", 12.9568, 77.5996, 1200, ["shantinagar"]),
    ("wilson-garden", "Wilson Garden", "ವಿಲ್ಸನ್ ಗಾರ್ಡನ್", 12.9489, 77.5968, 1500, []),
    ("domlur", "Domlur", "ದೊಮ್ಮಲೂರು", 12.9610, 77.6387, 1500, []),
    ("hal-old-airport-road", "HAL Old Airport Road", "ಹಳೆ ವಿಮಾನ ನಿಲ್ದಾಣ ರಸ್ತೆ", 12.9592, 77.6610, 2000, ["old airport road", "murugeshpalya"]),
    ("cv-raman-nagar", "CV Raman Nagar", "ಸಿವಿ ರಾಮನ್ ನಗರ", 12.9857, 77.6631, 2000, ["c v raman nagar"]),
    ("mahadevapura", "Mahadevapura", "ಮಹದೇವಪುರ", 12.9916, 77.6920, 2500, []),
    ("brookefield", "Brookefield", "ಬ್ರೂಕ್‌ಫೀಲ್ಡ್", 12.9667, 77.7180, 2000, ["brookfield"]),
    ("kadugodi", "Kadugodi", "ಕಾಡುಗೋಡಿ", 12.9973, 77.7586, 2500, []),
    ("varthur", "Varthur", "ವರ್ತೂರು", 12.9381, 77.7404, 2500, []),
    ("harlur", "Harlur", "ಹರಳೂರು", 12.9087, 77.6627, 2000, ["harlur road"]),
    ("bommanahalli", "Bommanahalli", "ಬೊಮ್ಮನಹಳ್ಳಿ", 12.9010, 77.6240, 2000, []),
    ("begur", "Begur", "ಬೇಗೂರು", 12.8758, 77.6280, 2500, ["begur road"]),
    ("hulimavu", "Hulimavu", "ಹುಳಿಮಾವು", 12.8780, 77.6010, 2000, []),
    ("bannerghatta-road", "Bannerghatta Road", "ಬನ್ನೇರುಘಟ್ಟ ರಸ್ತೆ", 12.8880, 77.5970, 3000, ["bg road", "bannerghatta"]),
    ("uttarahalli", "Uttarahalli", "ಉತ್ತರಹಳ್ಳಿ", 12.9055, 77.5455, 2500, []),
    ("padmanabhanagar", "Padmanabhanagar", "ಪದ್ಮನಾಭನಗರ", 12.9165, 77.5560, 1800, ["padmanabha nagar"]),
    ("kumaraswamy-layout", "Kumaraswamy Layout", "ಕುಮಾರಸ್ವಾಮಿ ಲೇಔಟ್", 12.9081, 77.5600, 1800, ["ks layout"]),
    ("girinagar", "Girinagar", "ಗಿರಿನಗರ", 12.9420, 77.5370, 1500, []),
    ("hanumanthanagar", "Hanumanthanagar", "ಹನುಮಂತನಗರ", 12.9420, 77.5560, 1500, ["hanumanth nagar"]),
    ("rr-nagar", "Rajarajeshwari Nagar", "ರಾಜರಾಜೇಶ್ವರಿ ನಗರ", 12.9273, 77.5157, 3000, ["rr nagar", "r r nagar"]),
    ("basaveshwaranagar", "Basaveshwaranagar", "ಬಸವೇಶ್ವರನಗರ", 12.9890, 77.5370, 2000, ["basaveshwara nagar"]),
    ("mahalakshmi-layout", "Mahalakshmi Layout", "ಮಹಾಲಕ್ಷ್ಮಿ ಲೇಔಟ್", 13.0110, 77.5480, 1500, []),
    ("yeshwanthpur", "Yeshwanthpur", "ಯಶವಂತಪುರ", 13.0284, 77.5401, 2500, ["yeshwantpur", "yesvantpur"]),
    ("mathikere", "Mathikere", "ಮತ್ತಿಕೆರೆ", 13.0330, 77.5600, 1500, []),
    ("sanjaynagar", "Sanjaynagar", "ಸಂಜಯನಗರ", 13.0330, 77.5770, 1500, ["sanjay nagar"]),
    ("sahakar-nagar", "Sahakar Nagar", "ಸಹಕಾರನಗರ", 13.0624, 77.5878, 2000, ["sahakarnagar"]),
    ("vidyaranyapura", "Vidyaranyapura", "ವಿದ್ಯಾರಣ್ಯಪುರ", 13.0790, 77.5580, 2500, []),
    ("jalahalli", "Jalahalli", "ಜಾಲಹಳ್ಳಿ", 13.0450, 77.5470, 2500, []),
    ("thanisandra", "Thanisandra", "ಥಣಿಸಂದ್ರ", 13.0560, 77.6330, 2500, []),
    ("jakkur", "Jakkur", "ಜಕ್ಕೂರು", 13.0780, 77.6060, 2500, []),
    ("banaswadi", "Banaswadi", "ಬಾಣಸವಾಡಿ", 13.0141, 77.6518, 2000, []),
    ("ramamurthy-nagar", "Ramamurthy Nagar", "ರಾಮಮೂರ್ತಿ ನಗರ", 13.0120, 77.6780, 2000, ["ramamurthynagar"]),
    ("horamavu", "Horamavu", "ಹೊರಮಾವು", 13.0270, 77.6590, 2000, []),
    ("cox-town", "Cox Town", "ಕಾಕ್ಸ್ ಟೌನ್", 12.9970, 77.6250, 1200, []),
    ("lingarajapuram", "Lingarajapuram", "ಲಿಂಗರಾಜಪುರಂ", 13.0120, 77.6250, 1500, []),
    ("kalasipalya", "Kalasipalya", "ಕಲಾಸಿಪಾಳ್ಯ", 12.9590, 77.5770, 1000, ["kalasipalyam"]),
    ("hosakerehalli", "Hosakerehalli", "ಹೊಸಕೆರೆಹಳ್ಳಿ", 12.9290, 77.5390, 1500, []),
]

# category slug -> (Google Places (New) Table A types for Nearby Search, text query for Text Search)
CATEGORY_DISCOVERY: dict[str, tuple[list[str], str]] = {
    "staples": (["grocery_store", "convenience_store"], "kirana provision store"),
    "supermarket": (["supermarket", "department_store"], "supermarket"),
    "snacks": (["grocery_store", "convenience_store"], "provision store"),
    "beverages": ([], "tea coffee powder shop"),
    "dairy": ([], "milk dairy shop"),
    "bakery": (["bakery"], "bakery"),
    "fruits_vegetables": (["market"], "fruits and vegetables shop"),
    "personal_care": (["drugstore"], "personal care store"),
    "household": (["home_goods_store"], "household items store"),
    "stationery": ([], "stationery shop"),
    "books": (["book_store"], "book store"),
    "electrical": ([], "electrical shop"),
    "hardware": (["hardware_store", "home_improvement_store", "building_materials_store"], "hardware shop"),
    "electronics": (["electronics_store"], "electronics shop"),
    "mobile_accessories": (["cell_phone_store"], "mobile accessories shop"),
    "computer_accessories": ([], "computer accessories shop"),
    "plumbing": ([], "plumbing and sanitary shop"),
    "paint": ([], "paint shop"),
    "home_appliances": ([], "home appliances store"),
    "furniture": (["furniture_store"], "furniture shop"),
    "kitchenware": ([], "kitchenware utensils shop"),
    "clothing": (["clothing_store"], "clothing store"),
    "footwear": (["shoe_store"], "footwear shop"),
    "tailoring": ([], "tailor shop"),
    "jewellery": (["jewelry_store"], "jewellery shop"),
    "cosmetics": ([], "cosmetics and beauty shop"),
    "pharmacy": (["pharmacy", "drugstore"], "pharmacy"),
    "medical_supplies": ([], "medical supplies shop"),
    "florist": (["florist"], "florist"),
    "toys": (["toy_store"], "toy shop"),
    "sports": (["sporting_goods_store"], "sports shop"),
    "bicycles": (["bicycle_store"], "bicycle shop"),
    "auto_parts": (["auto_parts_store"], "automobile spare parts shop"),
    "pet_supplies": (["pet_store"], "pet shop"),
    "gifts": (["gift_shop"], "gift shop"),
    "home_decor": ([], "home decor shop"),
    "wholesale": (["wholesaler"], "wholesale shop"),
    "agri_supplies": ([], "agricultural supplies shop"),
    "other": (["store"], "shop"),
}

# Default "all shops" discovery when no category is chosen.
GENERAL_RETAIL_TYPES = [
    "grocery_store", "supermarket", "convenience_store", "hardware_store", "electronics_store",
    "cell_phone_store", "pharmacy", "clothing_store", "book_store", "bakery", "store",
]

# Google primary type -> Milega category (for labelling externally discovered businesses).
GOOGLE_TYPE_TO_CATEGORY: dict[str, str] = {}
for _slug, (_types, _q) in CATEGORY_DISCOVERY.items():
    for _t in _types:
        GOOGLE_TYPE_TO_CATEGORY.setdefault(_t, _slug)
GOOGLE_TYPE_TO_CATEGORY.update({"discount_store": "supermarket", "shopping_mall": "other", "store": "other"})

# Words in a query that name a kind of *shop* rather than a product.
SHOP_CATEGORY_WORDS: dict[str, str] = {
    "kirana": "staples", "grocery": "staples", "groceries": "staples", "provision": "staples",
    "provisions": "staples", "supermarket": "supermarket", "mart": "supermarket",
    "stationery": "stationery", "stationary": "stationery", "xerox": "stationery",
    "bookshop": "books", "bookstore": "books", "books": "books",
    "electrical": "electrical", "electricals": "electrical", "electrician": "electrical",
    "hardware": "hardware", "electronics": "electronics", "mobile": "mobile_accessories",
    "mobiles": "mobile_accessories", "computer": "computer_accessories", "laptop": "computer_accessories",
    "plumbing": "plumbing", "sanitary": "plumbing", "paint": "paint", "paints": "paint",
    "appliances": "home_appliances", "furniture": "furniture", "kitchenware": "kitchenware",
    "utensils": "kitchenware", "vessels": "kitchenware", "clothing": "clothing", "clothes": "clothing",
    "garments": "clothing", "textiles": "clothing", "footwear": "footwear", "shoes": "footwear",
    "chappal": "footwear", "tailor": "tailoring", "tailors": "tailoring", "tailoring": "tailoring",
    "jewellery": "jewellery", "jewelry": "jewellery", "jewellers": "jewellery",
    "cosmetics": "cosmetics", "beauty": "cosmetics", "pharmacy": "pharmacy", "pharmacies": "pharmacy",
    "chemist": "pharmacy", "medical": "pharmacy", "medicals": "pharmacy", "medicine": "pharmacy",
    "florist": "florist", "flowers": "florist", "toys": "toys", "toy": "toys", "sports": "sports",
    "cycle": "bicycles", "bicycle": "bicycles", "spares": "auto_parts", "spare": "auto_parts",
    "automobile": "auto_parts", "pet": "pet_supplies", "pets": "pet_supplies", "gift": "gifts",
    "gifts": "gifts", "decor": "home_decor", "wholesale": "wholesale", "wholesaler": "wholesale",
    "bakery": "bakery", "bakeries": "bakery", "dairy": "dairy", "vegetables": "fruits_vegetables",
    "vegetable": "fruits_vegetables", "fruits": "fruits_vegetables", "agri": "agri_supplies",
    "fertilizer": "agri_supplies", "seeds": "agri_supplies",
    "किराना": "staples", "दवा": "pharmacy", "मेडिकल": "pharmacy", "हार्डवेयर": "hardware",
    "बेकरी": "bakery", "ದಿನಸಿ": "staples", "ಔಷಧಾಲಯ": "pharmacy", "ಬೇಕರಿ": "bakery", "ಹಾರ್ಡ್‌ವೇರ್": "hardware",
}
