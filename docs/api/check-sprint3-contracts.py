"""Validate the #139 handoff from the checked-in documents, not PASS prose.

From be-project-nexus: python docs/api/check-sprint3-contracts.py
Dependencies: python -m pip install PyYAML jsonschema
No network, database, credentials or running services are used by this check.
"""

import copy
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

import yaml
from jsonschema import Draft7Validator, FormatChecker

API = Path(__file__).resolve().parent
OWNERS = {
    'AuctionWon': ('auction', {171}, {172, 192}),
    'AuctionFailed': ('auction', {171}, {209, 192}),
    'OrderCreated': ('commerce', {118, 172}, {107, 173, 216, 192}),
    'OrderPaid': ('commerce', {156}, {160, 202, 192}),
    'OrderCancelled': ('commerce', {159, 189}, {161, 107, 157, 247, 208, 192}),
    'InventoryReservationExpired': ('inventory', {175}, {192}),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f'Duplicate JSON key: {key}')
        result[key] = value
    return result


class UniqueLoader(yaml.SafeLoader):
    pass


def yaml_mapping(loader, node):
    return unique_pairs(loader.construct_pairs(node, deep=True))


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, yaml_mapping)


def load_yaml(path):
    return yaml.load(path.read_text(encoding='utf-8'), Loader=UniqueLoader)


def json_blocks(text):
    return [json.loads(block, parse_float=Decimal, object_pairs_hook=unique_pairs)
            for block in re.findall(r'```json\n(.*?)\n```', text, re.S)]


def validate():
    markdown = (API / 'events.md').read_text(encoding='utf-8')
    all_blocks = json_blocks(markdown)
    parts = re.split(r'(?m)^### ([A-Z][A-Za-z]+)\n', markdown)
    sections = dict(zip(parts[1::2], parts[2::2]))
    checker = FormatChecker()
    require(all(f in checker.checkers for f in ('uuid', 'date-time', 'uri')),
            'UUID/date-time/URI format checks must be available')
    # Validate the untouched catalogue as well: local #139 edits must not break
    # another event's JSON schema or examples.
    for name, section in sections.items():
        blocks = json_blocks(section)
        require(len(blocks) >= 2, f'{name}: missing schema/example')
        Draft7Validator.check_schema(blocks[0])
        validator = Draft7Validator(blocks[0], format_checker=checker)
        for example in blocks[1:]:
            validator.validate(example)
    contracts = {}
    examples_checked = negatives = 0
    for name, (domain, publishers, consumers) in OWNERS.items():
        require(name in sections, f'{name}: missing section')
        section = sections[name]
        blocks = json_blocks(section)
        require(len(blocks) >= 2, f'{name}: missing schema/example')
        schema, examples = blocks[0], blocks[1:]
        require(schema.get('title') == name + 'Payload', f'{name}: schema title')
        Draft7Validator.check_schema(schema)
        validator = Draft7Validator(schema, format_checker=checker)
        for example in examples:
            validator.validate(example)
            examples_checked += 1
        for label, expected in [('Publisher', publishers), ('Consumer', consumers)]:
            match = re.search(r'^- ' + label + r': (.+)$', section, re.M)
            require(match is not None, f'{name}: missing {label}')
            actual = {int(n) for n in re.findall(r'\[#(\d+)\]', match[1])}
            require(actual == expected, f'{name}: wrong {label} issues: {actual}')
        require(f'- Topic: `nexus.{domain}.events`.' in section, f'{name}: topic')
        # Mutate actual valid examples, so required and format checks cannot be vacuous.
        for field in schema['required']:
            invalid = copy.deepcopy(examples[0])
            invalid.pop(field)
            require(not validator.is_valid(invalid), f'{name}.{field}: omission accepted')
            negatives += 1
        for field, prop in schema['properties'].items():
            bad_values = []
            if prop.get('format') in ('uuid', 'date-time', 'uri'):
                bad_values = ['not-a-' + prop['format']]
                if prop['format'] == 'date-time':
                    bad_values += ['2026-11-20T10:00:00', '2026-11-20T10:00:00+07:00']
            if prop.get('pattern') == r'^\d+\.\d{2}$':
                bad_values = [10000, '10000', '10000.1', '10000.001', '-1.00']
            for bad in bad_values:
                invalid = copy.deepcopy(examples[0])
                invalid[field] = bad
                require(not validator.is_valid(invalid), f'{name}.{field}: accepted {bad!r}')
                negatives += 1
        require(validator.is_valid({**examples[0], 'futureOptionalField': True}),
                f'{name}: additive-field tolerance changed')
        contracts[name] = (schema, examples)

    won, won_examples = contracts['AuctionWon']
    inputs = {'auctionId', 'winnerId', 'finalPrice', 'skuId', 'productId', 'title',
              'imageUrl', 'sellerId', 'paymentDueAt', 'currency', 'quantity', 'endAt'}
    require(inputs <= set(won['required']), 'AuctionWon: order-line inputs must be required')
    require(won['properties']['quantity'] == {'const': 1}, 'AuctionWon: quantity must be 1')
    require(won['properties']['imageUrl'].get('type') == 'string', 'AuctionWon: image cannot be null')
    # Verify the two documented legs agree on canonical buyer/seller/product/auction identity.
    auction_orders = [e for e in contracts['OrderCreated'][1] if e['orderType'] == 'AUCTION']
    require(len(auction_orders) == 1, 'OrderCreated: one AUCTION handoff example required')
    order, winner = auction_orders[0], won_examples[0]
    for target, source in [('buyerId', 'winnerId'), ('sellerId', 'sellerId'), ('referenceId', 'auctionId')]:
        require(order[target] == winner[source], f'AuctionWon -> OrderCreated: {target} drift')
    require(order['productIds'] == [winner['productId']], 'OrderCreated: productIds drift')
    require(winner['paymentDueAt'] > winner['endAt'], 'AuctionWon: deadline must follow endAt')
    for enum_name in ['OrderCreated', 'OrderPaid', 'OrderCancelled']:
        validator = Draft7Validator(contracts[enum_name][0], format_checker=checker)
        invalid = copy.deepcopy(contracts[enum_name][1][0])
        invalid.update(orderType='AUCTION', referenceId=None)
        require(not validator.is_valid(invalid), f'{enum_name}: AUCTION without reference accepted')
        invalid.update(orderType='STANDARD', referenceId=winner['auctionId'])
        require(not validator.is_valid(invalid), f'{enum_name}: STANDARD auction reference accepted')
        negatives += 2

    payment = load_yaml(API / 'openapi/payment-service.yaml')
    webhook = payment['paths']['/api/v1/webhooks/sepay']['post']
    capture = json.loads((API / 'sepay-webhook-sample.json').read_text(encoding='utf-8'))
    request = webhook['requestBody']['content']['application/json']
    require(request['example'] == capture, 'Webhook example must equal the captured JSON body')
    require(request['schema']['$ref'] == '#/components/schemas/SepayWebhookRequest',
            'Webhook must bind the provider schema')
    provider_schema = payment['components']['schemas']['SepayWebhookRequest']
    require(set(capture) <= set(provider_schema['properties']), 'Capture field missing from schema')
    Draft7Validator(provider_schema).validate(capture)
    dto = (API.parents[1] / 'payment-service/src/main/java/com/group5/paymentservice/payment/'
           'dto/request/SepayWebhookRequest.java').read_text(encoding='utf-8')
    for field in capture:
        require(re.search(r'\b(?:Long|String|BigDecimal)\s+' + field + r'\b', dto),
                f'Webhook capture.{field}: DTO binding missing')
    require(webhook['security'] == [], 'Webhook requires bearer authentication')

    auction = load_yaml(API / 'auction-service.yaml')
    state = auction['paths']['/api/v1/auctions/{id}/state']['get']
    require(state['security'] == [], 'State must be public')
    response = state['responses']['200']['content']['application/json']
    wrapper = auction['components']['schemas']['GetAuctionStateResponse']
    require({'code', 'message', 'data'} <= set(wrapper['required']), 'State response envelope')
    state_schema = auction['components']['schemas']['AuctionState']
    required_state = {'auctionId', 'currentPrice', 'bidCount', 'bidIncrement',
                      'leaderBidderName', 'endAt', 'status', 'serverTime'}
    require(set(state_schema['required']) == required_state, 'State response field inventory')
    require(set(state_schema['properties']) == required_state, 'Unexpected public state field')
    require(state_schema['properties']['leaderBidderName']['nullable'], 'No-bid leader must allow null')
    state_example = response['example']['data']
    Draft7Validator(state_schema, format_checker=checker).validate(state_example)
    require('*' in state_example['leaderBidderName'], 'State example exposes an unmasked leader')
    for field in ('endAt', 'serverTime'):
        require(re.search(r'(Z|[+-]\d\d:\d\d)$', state_example[field]), f'{field}: missing offset')
    permissions = (API / 'gateway-permissions.md').read_text(encoding='utf-8')
    require('`/api/v1/webhooks/**` | Public' in permissions, 'Public webhook contract family missing')
    require('`/api/v1/auctions/{id}/state` | Public' in permissions, 'Public state policy missing')

    print(f'PASS catalogue: {len(all_blocks)} JSON blocks; {len(sections)} schemas and their examples validate')
    print(f'PASS events: 6 schemas, {examples_checked} examples, publisher/consumer/topic mappings')
    print(f'PASS negative cases: {negatives}; additive fields and AUCTION identity handoff checked')
    print(f'PASS SePay: {len(capture)} captured fields bind schema/DTO; example equals original capture')
    print('PASS state: public, envelope/field inventory, masked example, explicit timestamp offsets')
    print('PASS gateway document: public state and webhook family declared')
    print('NOT CHECKED: owner approvals, image-source implementation, DB/Kafka/SePay integration, #168 performance')


if __name__ == '__main__':
    try:
        validate()
    except (ValueError, KeyError, json.JSONDecodeError, yaml.YAMLError) as exc:
        print(f'FAIL: {exc}', file=sys.stderr)
        raise SystemExit(1)
