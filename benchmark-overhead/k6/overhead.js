// One iteration lists specialties and pet types, creates an owner, adds a pet and a visit, reads the
// pet, deletes the pet and the owner, creates, reads and deletes a vet, and lists owners and vets.
// Every response status is checked: the harness fails a run whose checks pass rate is below 1.
//
// Each request carries a `name` tag, and the harness leaves the `url` system tag off, so a URL with
// an id in it does not start a metric series of its own. With WINDOW_MS set, every iteration is
// tagged with the third of the window it started in, and the thresholds below make k6 export the
// request count of the first and last thirds, which the harness compares to tell a warm JVM from
// one still compiling.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const WINDOW_MS = Number(__ENV.WINDOW_MS || 0);

export const options = WINDOW_MS > 0
  ? {
    thresholds: {
      'http_reqs{third:first}': ['count>=0'],
      'http_reqs{third:last}': ['count>=0'],
    },
  }
  : {};

const BASE = 'http://petclinic:9966/petclinic/api';
const JSON_TYPE = { 'Content-Type': 'application/json' };

function named(name) {
  return { tags: { name: name } };
}

function json(name) {
  return { headers: JSON_TYPE, tags: { name: name } };
}
const LETTERS = 'abcdefghijklmnopqrstuvwxyz';

function letters(length) {
  let out = '';
  for (let i = 0; i < length; i++) {
    out += LETTERS.charAt(Math.floor(Math.random() * LETTERS.length));
  }
  return out;
}

function name() {
  const word = letters(3 + Math.floor(Math.random() * 6));
  return word.charAt(0).toUpperCase() + word.slice(1);
}

function digits(length) {
  let out = '';
  for (let i = 0; i < length; i++) {
    out += Math.floor(Math.random() * 10);
  }
  return out;
}

function expect(res, status, label) {
  return check(res, { [label + ' is ' + status]: (r) => r.status === status });
}

function idOf(res) {
  try {
    return res.json('id');
  } catch (e) {
    return null;
  }
}

function owner() {
  return JSON.stringify({
    firstName: name(),
    lastName: name(),
    address: '110 W. Liberty St.',
    city: name(),
    telephone: digits(10),
  });
}

export default function () {
  if (WINDOW_MS > 0) {
    const third = Math.floor((3 * exec.instance.currentTestRunDuration) / WINDOW_MS);
    exec.vu.metrics.tags.third = third <= 0 ? 'first' : third === 1 ? 'middle' : 'last';
  }
  const specialties = http.get(BASE + '/specialties', named('GET /specialties'));
  expect(specialties, 200, 'GET /specialties');
  expect(http.get(BASE + '/pettypes', named('GET /pettypes')), 200, 'GET /pettypes');

  const created = http.post(BASE + '/owners', owner(), json('POST /owners'));
  expect(created, 201, 'POST /owners');
  const ownerId = idOf(created);

  const pet = http.post(
    BASE + '/owners/' + ownerId + '/pets',
    JSON.stringify({ name: name(), birthDate: '2020-12-31', type: { id: 2, name: 'dog' } }),
    json('POST /owners/{id}/pets'),
  );
  expect(pet, 201, 'POST /owners/{id}/pets');
  const petId = idOf(pet);

  expect(http.get(BASE + '/pets/' + petId, named('GET /pets/{id}')), 200, 'GET /pets/{id}');
  expect(
    http.post(
      BASE + '/owners/' + ownerId + '/pets/' + petId + '/visits',
      JSON.stringify({ date: '2024-05-01', description: letters(12) }),
      json('POST /owners/{id}/pets/{id}/visits'),
    ),
    201,
    'POST visits',
  );
  expect(http.del(BASE + '/pets/' + petId, null, named('DELETE /pets/{id}')), 204, 'DELETE /pets/{id}');
  expect(http.del(BASE + '/owners/' + ownerId, null, named('DELETE /owners/{id}')), 204, 'DELETE /owners/{id}');

  let specialty = { id: 1, name: 'radiology' };
  try {
    specialty = specialties.json()[0];
  } catch (e) {
    // The status check above already failed this iteration.
  }
  const vet = http.post(
    BASE + '/vets',
    JSON.stringify({ firstName: name(), lastName: name(), specialties: [specialty] }),
    json('POST /vets'),
  );
  expect(vet, 201, 'POST /vets');
  const vetId = idOf(vet);
  expect(http.get(BASE + '/vets/' + vetId, named('GET /vets/{id}')), 200, 'GET /vets/{id}');
  expect(http.del(BASE + '/vets/' + vetId, null, named('DELETE /vets/{id}')), 204, 'DELETE /vets/{id}');

  expect(http.get(BASE + '/owners', named('GET /owners')), 200, 'GET /owners');
  expect(http.get(BASE + '/vets', named('GET /vets')), 200, 'GET /vets');
}
