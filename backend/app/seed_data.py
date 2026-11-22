"""Starter catalogue for Indian neighbourhood retail.

Reference prices are *only* used to generate clearly-labelled demo shops in
development. They are approximate and must never be shown as real prices.
"""

from __future__ import annotations

CATEGORIES = [
    # slug, English, Hindi, Kannada, icon, sort
    ("staples", "Staples & Kirana", "किराना", "ದಿನಸಿ", "grain", 10),
    ("snacks", "Snacks & Biscuits", "नमकीन और बिस्किट", "ತಿಂಡಿ ಮತ್ತು ಬಿಸ್ಕತ್", "cookie", 20),
    ("beverages", "Tea, Coffee & Drinks", "चाय, कॉफी और पेय", "ಚಹಾ, ಕಾಫಿ ಮತ್ತು ಪಾನೀಯ", "cup", 30),
    ("dairy", "Dairy", "डेयरी", "ಹಾಲಿನ ಉತ್ಪನ್ನಗಳು", "milk", 40),
    ("fruits_vegetables", "Fruits & Vegetables", "फल और सब्ज़ियाँ", "ಹಣ್ಣು ಮತ್ತು ತರಕಾರಿ", "leaf", 50),
    ("personal_care", "Personal Care", "पर्सनल केयर", "ವೈಯಕ್ತಿಕ ಆರೈಕೆ", "soap", 60),
    ("household", "Household", "घरेलू सामान", "ಮನೆಬಳಕೆ", "home", 70),
    ("stationery", "Stationery", "स्टेशनरी", "ಲೇಖನ ಸಾಮಗ್ರಿ", "pencil", 80),
    ("electrical", "Electrical", "इलेक्ट्रिकल", "ವಿದ್ಯುತ್ ಸಾಮಗ್ರಿ", "bolt", 90),
    ("hardware", "Hardware", "हार्डवेयर", "ಹಾರ್ಡ್‌ವೇರ್", "wrench", 100),
    ("mobile_accessories", "Mobile Accessories", "मोबाइल एक्सेसरीज़", "ಮೊಬೈಲ್ ಆಕ್ಸೆಸರೀಸ್", "phone", 110),
    ("other", "Other", "अन्य", "ಇತರೆ", "box", 999),
]

# (brand, product name, category, [(size label, reference price ₹)], sell_unit, aliases)
CATALOG: list[tuple] = [
    # --- staples
    ("Aashirvaad", "Shudh Chakki Atta", "staples", [("1 kg", 68), ("5 kg", 285), ("10 kg", 540)], "pc",
     ["atta", "wheat flour", "आशीर्वाद आटा", "आटा", "ಆಶೀರ್ವಾದ್ ಹಿಟ್ಟು", "ಗೋಧಿ ಹಿಟ್ಟು"]),
    ("Pillsbury", "Chakki Fresh Atta", "staples", [("5 kg", 270)], "pc", ["atta", "आटा", "ಹಿಟ್ಟು"]),
    ("Tata", "Salt", "staples", [("1 kg", 28)], "pc", ["namak", "टाटा नमक", "नमक", "ಟಾಟಾ ಉಪ್ಪು", "ಉಪ್ಪು", "iodised salt"]),
    ("Tata", "Salt Lite", "staples", [("1 kg", 45)], "pc", ["low sodium salt", "नमक"]),
    ("Fortune", "Sunflower Refined Oil", "staples", [("1 L", 165), ("5 L", 790)], "pc",
     ["sunflower oil", "तेल", "फॉर्च्यून तेल", "ಎಣ್ಣೆ", "ಸೂರ್ಯಕಾಂತಿ ಎಣ್ಣೆ"]),
    ("Fortune", "Kachi Ghani Mustard Oil", "staples", [("1 L", 185)], "pc", ["sarson tel", "सरसों तेल", "mustard oil"]),
    ("Gold Winner", "Refined Sunflower Oil", "staples", [("1 L", 160)], "pc", ["sunflower oil", "ಎಣ್ಣೆ"]),
    ("India Gate", "Basmati Rice Classic", "staples", [("1 kg", 210)], "pc", ["basmati", "chawal", "चावल", "ಬಾಸ್ಮತಿ ಅಕ್ಕಿ"]),
    ("Tata Sampann", "Toor Dal Unpolished", "staples", [("1 kg", 185)], "pc", ["arhar dal", "तूर दाल", "ತೊಗರಿ ಬೇಳೆ"]),
    ("Tata Sampann", "Moong Dal", "staples", [("500 g", 95)], "pc", ["मूंग दाल", "ಹೆಸರು ಬೇಳೆ"]),
    ("MDH", "Deggi Mirch", "staples", [("100 g", 90)], "pc", ["chilli powder", "mirchi", "मिर्च"]),
    ("Everest", "Garam Masala", "staples", [("100 g", 85)], "pc", ["masala", "गरम मसाला"]),
    ("Madhur", "Pure Sugar", "staples", [("1 kg", 55)], "pc", ["chini", "चीनी", "ಸಕ್ಕರೆ"]),
    (None, "Sona Masoori Rice (Loose)", "staples", [(None, 62)], "kg", ["sona masuri", "rice", "चावल", "ಅಕ್ಕಿ"]),
    (None, "Toor Dal (Loose)", "staples", [(None, 160)], "kg", ["arhar dal", "तूर दाल", "ತೊಗರಿ ಬೇಳೆ"]),
    (None, "Sugar (Loose)", "staples", [(None, 46)], "kg", ["chini", "चीनी", "ಸಕ್ಕರೆ"]),
    (None, "Wheat Atta (Loose)", "staples", [(None, 42)], "kg", ["chakki atta", "आटा", "ಗೋಧಿ ಹಿಟ್ಟು"]),
    # --- snacks
    ("Parle", "Parle-G Original Glucose Biscuits", "snacks", [("250 g", 25), ("800 g", 80)], "pc",
     ["parle g", "पारले जी", "पारले-जी", "ಪಾರ್ಲೆ ಜಿ", "glucose biscuit"]),
    ("Britannia", "Good Day Cashew Cookies", "snacks", [("200 g", 40)], "pc", ["good day", "गुड डे"]),
    ("Britannia", "Marie Gold Biscuits", "snacks", [("250 g", 35)], "pc", ["marie", "मैरी गोल्ड"]),
    ("Sunfeast", "Dark Fantasy Choco Fills", "snacks", [("75 g", 40)], "pc", ["dark fantasy"]),
    ("Maggi", "2-Minute Masala Noodles", "snacks", [("70 g", 15), ("280 g", 56), ("560 g", 108)], "pc",
     ["nestle maggi", "मैगी", "ಮ್ಯಾಗಿ", "instant noodles"]),
    ("Sunfeast", "Yippee Magic Masala Noodles", "snacks", [("70 g", 15)], "pc", ["yippee", "यिप्पी"]),
    ("Haldiram's", "Aloo Bhujia", "snacks", [("200 g", 55), ("400 g", 105)], "pc", ["haldiram", "namkeen", "नमकीन", "भुजिया"]),
    ("Lay's", "India's Magic Masala Chips", "snacks", [("50 g", 20)], "pc", ["lays", "chips", "चिप्स"]),
    ("Kurkure", "Masala Munch", "snacks", [("90 g", 20)], "pc", ["कुरकुरे"]),
    # --- beverages
    ("Tata Tea", "Premium Tea", "beverages", [("250 g", 140), ("1 kg", 520)], "pc", ["tata chai", "चाय", "ಟೀ ಪುಡಿ"]),
    ("Brooke Bond", "Red Label Tea", "beverages", [("250 g", 145)], "pc", ["red label", "चाय"]),
    ("Nescafe", "Classic Instant Coffee", "beverages", [("50 g", 185)], "pc", ["coffee", "कॉफी", "ಕಾಫಿ"]),
    ("Bru", "Instant Coffee", "beverages", [("100 g", 230)], "pc", ["coffee", "ಕಾಫಿ ಪುಡಿ"]),
    ("Cadbury", "Bournvita Health Drink", "beverages", [("500 g", 245)], "pc", ["bournvita", "बोर्नविटा"]),
    ("Thums Up", "Soft Drink", "beverages", [("750 ml", 40), ("2 L", 99)], "pc", ["thumbs up", "cold drink", "कोल्ड ड्रिंक"]),
    ("Bisleri", "Packaged Drinking Water", "beverages", [("1 L", 20)], "pc", ["water bottle", "पानी", "ನೀರು"]),
    # --- dairy
    ("Amul", "Butter", "dairy", [("100 g", 58), ("500 g", 285)], "pc", ["makhan", "मक्खन", "ಬೆಣ್ಣೆ"]),
    ("Amul", "Taaza Toned Milk", "dairy", [("500 ml", 28)], "pc", ["milk packet", "दूध", "ಹಾಲು"]),
    ("Nandini", "Toned Milk", "dairy", [("500 ml", 24)], "pc", ["nandini milk", "ನಂದಿನಿ ಹಾಲು", "ಹಾಲು"]),
    ("Nandini", "Curd", "dairy", [("500 g", 30)], "pc", ["dahi", "दही", "ಮೊಸರು"]),
    ("Amul", "Cheese Slices", "dairy", [("200 g", 145)], "pc", ["cheese", "चीज़"]),
    # --- fruits & vegetables (loose)
    (None, "Onion", "fruits_vegetables", [(None, 38)], "kg", ["pyaz", "प्याज", "ಈರುಳ್ಳಿ"]),
    (None, "Tomato", "fruits_vegetables", [(None, 30)], "kg", ["tamatar", "टमाटर", "ಟೊಮೇಟೊ"]),
    (None, "Potato", "fruits_vegetables", [(None, 32)], "kg", ["aloo", "आलू", "ಆಲೂಗಡ್ಡೆ"]),
    (None, "Banana (Robusta)", "fruits_vegetables", [(None, 50)], "dozen", ["kela", "केला", "ಬಾಳೆಹಣ್ಣು"]),
    # --- personal care
    ("Colgate", "Strong Teeth Toothpaste", "personal_care", [("100 g", 58), ("200 g", 110)], "pc", ["toothpaste", "कोलगेट", "ಕೋಲ್ಗೇಟ್"]),
    ("Dabur", "Red Toothpaste", "personal_care", [("100 g", 62)], "pc", ["dabur red", "toothpaste"]),
    ("Lifebuoy", "Total 10 Soap", "personal_care", [("125 g", 38)], "pc", ["lifebuoy", "sabun", "साबुन", "ಸೋಪು"]),
    ("Dove", "Cream Beauty Bathing Bar", "personal_care", [("100 g", 65)], "pc", ["dove soap", "साबुन"]),
    ("Santoor", "Sandal & Turmeric Soap", "personal_care", [("100 g", 40)], "pc", ["santoor", "साबुन", "ಸಂತೂರ್"]),
    ("Clinic Plus", "Strong & Long Shampoo", "personal_care", [("175 ml", 125)], "pc", ["shampoo", "शैम्पू"]),
    ("Parachute", "Coconut Oil", "personal_care", [("250 ml", 120)], "pc", ["nariyal tel", "नारियल तेल", "ಕೊಬ್ಬರಿ ಎಣ್ಣೆ"]),
    ("Dettol", "Antiseptic Liquid", "personal_care", [("125 ml", 85)], "pc", ["dettol", "डेटॉल"]),
    # --- household
    ("Surf Excel", "Easy Wash Detergent Powder", "household", [("1 kg", 140)], "pc", ["surf", "washing powder", "सर्फ"]),
    ("Tide", "Plus Detergent Powder", "household", [("1 kg", 115)], "pc", ["washing powder", "टाइड"]),
    ("Rin", "Detergent Bar", "household", [("250 g", 30)], "pc", ["washing soap", "रिन"]),
    ("Vim", "Dishwash Bar", "household", [("200 g", 20)], "pc", ["vim bar", "बर्तन साबुन"]),
    ("Harpic", "Power Plus Toilet Cleaner", "household", [("500 ml", 99)], "pc", ["toilet cleaner"]),
    ("Good Knight", "Gold Flash Refill", "household", [("45 ml", 85)], "pc", ["mosquito", "machhar", "मच्छर"]),
    # --- stationery
    ("Classmate", "Single Line Notebook", "stationery", [("172 pages", 65), ("240 pages", 85)], "pc",
     ["notebook", "copy", "नोटबुक", "ನೋಟ್ಬುಕ್"]),
    ("Classmate", "Long Notebook Unruled", "stationery", [("172 pages", 60)], "pc", ["notebook", "copy"]),
    ("Reynolds", "045 Fine Carbure Ball Pen Blue", "stationery", [("1 pc", 10)], "pc", ["pen", "पेन", "ಪೆನ್"]),
    ("Cello", "Butterflow Ball Pen Blue", "stationery", [("1 pc", 10)], "pc", ["pen", "पेन"]),
    ("Apsara", "Platinum Extra Dark Pencils (Pack of 10)", "stationery", [("10 pcs", 50)], "pc", ["pencil", "पेंसिल"]),
    ("Fevicol", "MR White Adhesive", "stationery", [("50 g", 20)], "pc", ["glue", "gum", "गोंद"]),
    ("JK", "Copier A4 Paper 75 GSM (500 sheets)", "stationery", [(None, 330)], "pc", ["a4 paper", "xerox paper"]),
    # --- electrical
    ("Philips", "LED Bulb B22", "electrical", [("9 W", 99)], "pc", ["bulb", "बल्ब", "ಬಲ್ಬ್", "led"]),
    ("Syska", "LED Bulb B22", "electrical", [("12 W", 120)], "pc", ["bulb", "बल्ब"]),
    ("Havells", "Adore LED Bulb", "electrical", [("9 W", 95)], "pc", ["bulb", "बल्ब"]),
    ("Anchor", "Roma Switch", "electrical", [("6 A", 30)], "pc", ["switch", "स्विच"]),
    ("Havells", "Lifeline Plus FR PVC Wire 90 m", "electrical", [("1.5 sqmm", 1650)], "pc", ["wire", "तार", "ವೈರ್"]),
    ("Eveready", "AA Battery (Pack of 2)", "electrical", [("2 pcs", 40)], "pc", ["cell", "battery", "बैटरी"]),
    ("Duracell", "AA Alkaline Battery (Pack of 2)", "electrical", [("2 pcs", 110)], "pc", ["cell", "battery"]),
    ("Anchor", "Extension Board 4 Socket", "electrical", [(None, 450)], "pc", ["extension", "spike guard"]),
    # --- hardware
    ("Supreme", "PVC Pipe Class 3 (6 m)", "hardware", [("2 inch", 520), ("1 inch", 260)], "pc", ["pvc pipe", "पाइप", "ಪೈಪ್"]),
    ("Asian Paints", "Tractor Emulsion White", "hardware", [("1 L", 260)], "pc", ["paint", "पेंट"]),
    ("Fevicol", "SH Synthetic Resin Adhesive", "hardware", [("1 kg", 380)], "pc", ["fevicol", "wood glue"]),
    ("Godrej", "Navtal 6 Lever Padlock", "hardware", [(None, 650)], "pc", ["lock", "taala", "ताला", "ಬೀಗ"]),
    (None, "Wire Nails 2 inch (Loose)", "hardware", [(None, 90)], "kg", ["nails", "keel", "कील", "ಮೊಳೆ"]),
    # --- mobile accessories
    ("Samsung", "Fast Charger USB Type-C", "mobile_accessories", [("25 W", 1299)], "pc",
     ["charger", "adapter", "चार्जर", "ಚಾರ್ಜರ್", "travel adapter"]),
    ("Xiaomi", "Mi Fast Charger Type-C", "mobile_accessories", [("33 W", 999)], "pc", ["mi charger", "redmi charger", "चार्जर"]),
    ("boAt", "Type-C to Type-C Cable", "mobile_accessories", [("1.5 m", 299)], "pc", ["data cable", "charging cable", "usb c cable"]),
    ("Ambrane", "USB to Type-C Cable", "mobile_accessories", [("1 m", 199)], "pc", ["data cable", "charging cable"]),
    ("Portronics", "Micro USB Cable", "mobile_accessories", [("1 m", 149)], "pc", ["data cable", "v8 cable"]),
    ("boAt", "Bassheads 100 Wired Earphones", "mobile_accessories", [(None, 399)], "pc", ["earphones", "handsfree", "ईयरफोन"]),
    (None, "Tempered Glass Screen Guard", "mobile_accessories", [(None, 150)], "pc", ["screen guard", "glass"]),
]
