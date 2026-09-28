const { test, describe } = require('node:test');
const assert = require('node:assert/strict');

/**
 * Hydration Merge Guard Algorithm
 * Applied uniformly across all entities (attendance, tasks, customers, visits, expenses)
 */
function mergeServerDataWithLocal({ localEntities, serverEntities, pendingQueueIds }) {
  const localMap = new Map(localEntities.map(e => [e.id, e]));
  const pendingSet = new Set(pendingQueueIds);

  const merged = [];

  for (const serverEntity of serverEntities) {
    const localEntity = localMap.get(serverEntity.id);

    if (!localEntity) {
      // Not locally known: take server copy
      merged.push({ ...serverEntity, syncState: 'SYNCED' });
      continue;
    }

    // HYDRATION MERGE GUARD:
    // If local row's syncState != 'SYNCED' OR its id/operationId is in pending/failed sync_queue,
    // LOCAL PENDING WORK WINS!
    const isPendingSync = localEntity.syncState !== 'SYNCED';
    const isInQueue = pendingSet.has(localEntity.id) || (localEntity.operationId && pendingSet.has(localEntity.operationId));

    if (isPendingSync || isInQueue) {
      // PRESERVE LOCAL WORK
      merged.push(localEntity);
    } else {
      // Clean to update from server
      merged.push({ ...serverEntity, syncState: 'SYNCED' });
    }
  }

  // Also retain any local entities created offline that do NOT yet exist on the server
  for (const localEntity of localEntities) {
    if (!serverEntities.some(s => s.id === localEntity.id)) {
      merged.push(localEntity);
    }
  }

  return merged;
}

describe('Phase 1 Item 8 & Verification 7: Hydration Merge Guard', () => {
  test('Two devices, same account: pending offline change on device A is preserved on refresh', () => {
    // Device A made an offline update to Task 1
    const localEntities = [
      {
        id: 'task-101',
        title: 'Inspect North Gate Motors (Offline Edit)',
        status: 'IN_PROGRESS',
        operationId: 'op-task-offline-101',
        syncState: 'PENDING'
      },
      {
        id: 'task-102',
        title: 'Check Warehouse Inventory',
        status: 'COMPLETED',
        operationId: 'op-task-102',
        syncState: 'SYNCED'
      }
    ];

    const pendingQueueIds = ['op-task-offline-101'];

    // Server returns older/stale state (e.g. from Device B or prior state)
    const serverEntities = [
      {
        id: 'task-101',
        title: 'Inspect North Gate Motors (Stale Server State)',
        status: 'PENDING'
      },
      {
        id: 'task-102',
        title: 'Check Warehouse Inventory',
        status: 'COMPLETED'
      },
      {
        id: 'task-103',
        title: 'New Task Created by Admin',
        status: 'PENDING'
      }
    ];

    const result = mergeServerDataWithLocal({ localEntities, serverEntities, pendingQueueIds });

    // 1. Task 101 MUST retain the offline edit
    const task101 = result.find(t => t.id === 'task-101');
    assert.equal(task101.title, 'Inspect North Gate Motors (Offline Edit)');
    assert.equal(task101.status, 'IN_PROGRESS');
    assert.equal(task101.syncState, 'PENDING');

    // 2. Task 102 was already synced, so it is cleanly merged
    const task102 = result.find(t => t.id === 'task-102');
    assert.equal(task102.id, 'task-102');
    assert.equal(task102.syncState, 'SYNCED');

    // 3. Task 103 was new on server, so it is pulled in
    const task103 = result.find(t => t.id === 'task-103');
    assert.equal(task103.id, 'task-103');
    assert.equal(task103.syncState, 'SYNCED');
  });

  test('Local new record created completely offline survives server hydration', () => {
    const localEntities = [
      {
        id: 'customer-new-uuid',
        name: 'New Offline Customer Ltd',
        phone: '+919876543210',
        syncState: 'PENDING',
        operationId: 'op-cust-create-1'
      }
    ];

    const serverEntities = [];
    const pendingQueueIds = ['op-cust-create-1'];

    const result = mergeServerDataWithLocal({ localEntities, serverEntities, pendingQueueIds });
    assert.equal(result.length, 1);
    assert.equal(result[0].id, 'customer-new-uuid');
    assert.equal(result[0].name, 'New Offline Customer Ltd');
    assert.equal(result[0].syncState, 'PENDING');
  });
});

module.exports = { mergeServerDataWithLocal };
