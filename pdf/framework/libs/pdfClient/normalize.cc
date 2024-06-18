/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "normalize.h"

#include <stdint.h>

#include <algorithm>
#include <string>

#include "utf.h"

namespace pdfClient {

namespace {

// pdfClient normally reports line breaks as "\r\n". But when a line ends with
// a hyphen, pdfClient reports the hyphen and the line break together as '\x2'.
const char32_t kBrokenWordMarker = '\x2';
const char32_t kCarriageReturn = '\r';
const char32_t kLineFeed = '\n';

const char* kGroups[] = {
        // Treat the broken word marker the same as a hyphen when searching.
        "-\x2",
        // Space, tab and newline are all treated as equivalent when searching.
        " \t\r\n\u00A0",
        // Put upper,lower,and accented variants of the same letter in the same group
        // for searching. Generated using data from java.lang.Character
        "aAªÀÁÂÃÄÅàáâãäåĀāĂăĄąǍǎǞǟǠǡǺǻȀȁȂȃȦȧ", "bB", "cCÇçĆćĈĉĊċČč", "dDĎďǄǅǆǱǲǳ",
        "eEÈÉÊËèéêëĒēĔĕĖėĘęĚěȄȅȆȇȨȩ", "fF", "gGĜĝĞğĠġĢģǦǧǴǵ", "hHĤĥȞȟ",
        "iIÌÍÎÏìíîïĨĩĪīĬĭĮįİĲĳǏǐȈȉȊȋ", "jJĴĵǰ", "kKĶķǨǩ", "lLĹĺĻļĽľĿŀǇǈǉ", "mM", "nNÑñŃńŅņŇňŉǊǋǌǸǹ",
        "oOºÒÓÔÕÖòóôõöŌōŎŏŐőƠơǑǒǪǫǬǭȌȍȎȏȪȫȬȭȮȯȰȱ", "pP", "qQ", "rRŔŕŖŗŘřȐȑȒȓ", "sSŚśŜŝŞşŠšſȘș",
        "tTŢţŤťȚț", "uUÙÚÛÜùúûüŨũŪūŬŭŮůŰűŲųƯưǓǔǕǖǗǘǙǚǛǜȔȕȖȗ", "vV", "wWŴŵ", "xX", "yYÝýÿŶŷŸȲȳ",
        "zZŹźŻżŽž", "æÆǢǣǼǽ", "ðÐ", "øØǾǿ", "þÞ", "đĐ", "ħĦ", "łŁ", "ŋŊ", "œŒ", "ŧŦ", "ƀɃ", "ƃƂ",
        "ƅƄ", "ƈƇ", "ƌƋ", "ƒƑ", "ƕǶ", "ƙƘ", "ƚȽ", "ƞȠ", "ƣƢ", "ƥƤ", "ƨƧ", "ƭƬ", "ƴƳ", "ƶƵ", "ƹƸ",
        "ƽƼ", "ƿǷ", "ǝƎ", "ǥǤ", "ȝȜ", "ȣȢ", "ȥȤ", "ȼȻ", "ɂɁ", "ɇɆ", "ɉɈ", "ɋɊ", "ɍɌ", "ɏɎ", "ɓƁ",
        "ɔƆ", "ɖƉ", "ɗƊ", "əƏ", "ɛƐ", "ɠƓ", "ɣƔ", "ɨƗ", "ɩƖ", "ɯƜ", "ɲƝ", "ɵƟ", "ʀƦ", "ʃƩ", "ʈƮ",
        "ʉɄ", "ʊƱ", "ʋƲ", "ʌɅ", "ʒƷǮǯ", "ͱͰ", "ͳͲ", "ͷͶ", "ͻϽ", "ͼϾ", "ͽϿ", "αΆΑά", "βΒϐ", "γΓ",
        "δΔ", "εΈΕέϵ", "ζΖ", "ηΉΗή", "θΘϑϴ", "ιΊΐΙΪίϊ", "κΚϰ", "λΛ", "μµΜ", "νΝ", "ξΞ", "οΌΟό",
        "πΠϖ", "ρΡϱ", "ςϲ", "σΣϹ", "τΤ", "υΎΥΫΰϋύϒϓϔ", "φΦϕ", "χΧ", "ψΨ", "ωΏΩώ", "ϗϏ", "ϙϘ", "ϛϚ",
        "ϝϜ", "ϟϞ", "ϡϠ", "ϣϢ", "ϥϤ", "ϧϦ", "ϩϨ", "ϫϪ", "ϭϬ", "ϯϮ", "ϸϷ", "ϻϺ", "аАӐӑӒӓ", "бБ",
        "вВ", "гЃГѓ", "дД", "еЀЁЕѐёӖӗ", "жЖӁӂӜӝ", "зЗӞӟ", "иЍИЙйѝӢӣӤӥ", "кЌКќ", "лЛ", "мМ", "нН",
        "оОӦӧ", "пП", "рР", "сС", "тТ", "уЎУўӮӯӰӱӲӳ", "фФ", "хХ", "цЦ", "чЧӴӵ", "шШ", "щЩ", "ъЪ",
        "ыЫӸӹ", "ьЬ", "эЭӬӭ", "юЮ", "яЯ", "ђЂ", "єЄ", "ѕЅ", "іІЇї", "јЈ", "љЉ", "њЊ", "ћЋ", "џЏ",
        "ѡѠ", "ѣѢ", "ѥѤ", "ѧѦ", "ѩѨ", "ѫѪ", "ѭѬ", "ѯѮ", "ѱѰ", "ѳѲ", "ѵѴѶѷ", "ѹѸ", "ѻѺ", "ѽѼ", "ѿѾ",
        "ҁҀ", "ҋҊ", "ҍҌ", "ҏҎ", "ґҐ", "ғҒ", "ҕҔ", "җҖ", "ҙҘ", "қҚ", "ҝҜ", "ҟҞ", "ҡҠ", "ңҢ", "ҥҤ",
        "ҧҦ", "ҩҨ", "ҫҪ", "ҭҬ", "үҮ", "ұҰ", "ҳҲ", "ҵҴ", "ҷҶ", "ҹҸ", "һҺ", "ҽҼ", "ҿҾ", "ӄӃ", "ӆӅ",
        "ӈӇ", "ӊӉ", "ӌӋ", "ӎӍ", "ӏӀ", "ӕӔ", "әӘӚӛ", "ӡӠ", "өӨӪӫ", "ӷӶ", "ӻӺ", "ӽӼ"};

const size_t kNumGroups = sizeof(kGroups) / sizeof(kGroups[0]);

// All of the characters that are normalized have codepoints of < 0x500.
const size_t kTableSize = 0x500;

const uint16_t* CreateTable() {
    static uint16_t table[kTableSize];
    for (size_t i = 0; i < kTableSize; i++) {
        table[i] = i;
    }
    for (size_t i = 0; i < kNumGroups; i++) {
        std::u32string group = Utf8ToUtf32(kGroups[i]);
        for (size_t j = 0; j < group.length(); j++) {
            table[group[j]] = group[0];
        }
    }
    return table;
}

}  // namespace

char32_t NormalizeForSearch(char32_t codepoint) {
    // Table is created on first use and cached.
    static const uint16_t* table = CreateTable();
    if (codepoint < kTableSize) {
        return table[codepoint];
    }
    return codepoint;
}

bool BothAreSpaces(char32_t left_codepoint, char32_t right_codepoint) {
    return left_codepoint == '\x20' && right_codepoint == '\x20';
}

void NormalizeStringForSearch(std::u32string* utf32) {
    std::transform(utf32->begin(), utf32->end(), utf32->begin(), NormalizeForSearch);
    // Collapse repeated whitespace into a single space:
    utf32->erase(std::unique(utf32->begin(), utf32->end(), BothAreSpaces), utf32->end());
}

bool IsSkippableForSearch(char32_t codepoint, char32_t prev_codepoint) {
    if (codepoint == kBrokenWordMarker) {
        // This can be skipped so words can be found when broken onto two lines.
        return true;
    }
    if (BothAreSpaces(NormalizeForSearch(codepoint), NormalizeForSearch(prev_codepoint))) {
        // Repeated whitespace can be skipped so that all whitespace is equivalent.
        return true;
    }
    return false;
}

bool IsLineBreak(char32_t codepoint) {
    switch (codepoint) {
        case kBrokenWordMarker:
        case kLineFeed:
            return true;
        default:
            return false;
    }
}

bool IsWordBreak(char32_t codepoint) {
    char32_t normalized = NormalizeForSearch(codepoint);
    return normalized == ' ' || normalized == '-';
}

void AppendpdfClientCodepointAsUtf8(char32_t codepoint, std::string* output) {
    if (codepoint == kBrokenWordMarker) {
        output->append("-\r\n");  // We give the user what the text looks like.
    } else {
        AppendCodepointAsUtf8(codepoint, output);
    }
}

}  // namespace pdfClient