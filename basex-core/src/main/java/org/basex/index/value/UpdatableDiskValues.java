package org.basex.index.value;

import static org.basex.data.DataText.*;
import static org.basex.util.Token.*;

import java.io.*;

import org.basex.data.*;
import org.basex.util.*;
import org.basex.util.hash.*;
import org.basex.util.list.*;

/**
 * This class provides access and update function to attribute values and text contents stored on
 * disk. The data structure is described in the {@link DiskValuesBuilder} class.
 *
 * @author BaseX Team 2005-14, BSD License
 * @author Dimitar Popov
 */
public final class UpdatableDiskValues extends DiskValues {
  /**
   * Constructor, initializing the index structure.
   * @param data data reference
   * @param text value type (texts/attributes)
   * @throws IOException I/O Exception
   */
  public UpdatableDiskValues(final Data data, final boolean text) throws IOException {
    this(data, text, text ? DATATXT : DATAATV);
  }

  /**
   * Constructor, initializing the index structure.
   * @param data data reference
   * @param text value type (texts/attributes)
   * @param prefix file prefix
   * @throws IOException I/O Exception
   */
  private UpdatableDiskValues(final Data data, final boolean text, final String prefix)
      throws IOException {
    super(data, text, prefix);
  }

  @Override
  protected int pre(final int id) {
    return data.pre(id);
  }

  @Override
  public synchronized void add(final TokenObjMap<IntList> map) {
    final int s = size();
    final int last = s - 1;

    // create a sorted list of all keys: allows faster binary search
    final TokenList allkeys = new TokenList(map).sort(true);

    // create a sorted list of the new keys and update the old keys
    final TokenList nkeys = new TokenList(map.size());
    int p = 0;
    for(final byte[] key : allkeys) {
      p = get(key, p, s);
      if(p < 0) {
        p = -(p + 1);
        nkeys.add(key);
      } else {
        appendIds(p++, key, diffs(map.get(key)));
      }
    }

    // insert new keys, starting from the biggest one
    for(int j = nkeys.size() - 1, i = last, pos = s + j; j >= 0; --j) {
      final byte[] key = nkeys.get(j);

      final int in = -(1 + get(key, 0, i + 1));
      if(in < 0) throw Util.notExpected("Key should not exist: '" + string(key) + '\'');

      // shift all bigger keys to the right
      while(i >= in) {
        idxr.write5(pos * 5L, idxr.read5(i * 5L));
        ctext.put(pos--, ctext.get(i--));
      }

      // add the new key and its ids
      idxr.write5(pos * 5L, idxl.appendNums(diffs(map.get(key))));
      ctext.put(pos--, key);
      // [DP] should the entry be added to the cache?
    }

    size(s + nkeys.size());
  }

  /**
   * Add record ids to an index entry.
   * @param ix index of the key
   * @param key key
   * @param nids sorted list of record ids to add: the first value is the
   * smallest id and all others are only difference to the previous one
   */
  private void appendIds(final int ix, final byte[] key, final int[] nids) {
    final long oldpos = idxr.read5(ix * 5L);
    final int numold = idxl.readNum(oldpos);
    final int[] ids = new int[numold + nids.length];

    // read the old ids
    for(int i = 0; i < numold; ++i) {
      final int v = idxl.readNum();
      nids[0] -= v; // adjust the first new id
      ids[i] = v;
    }

    // append the new ids - they are bigger than the old ones
    System.arraycopy(nids, 0, ids, numold, nids.length);

    final long newpos = idxl.appendNums(ids);
    idxr.write5(ix * 5L, newpos);

    // update the cache entry
    cache.add(key, ids.length, newpos + Num.length(ids.length));
  }

  @Override
  public synchronized void delete(final TokenObjMap<IntList> map) {
    // create a sorted list of all keys: allows faster binary search
    final TokenList allkeys = new TokenList(map).sort(true);

    // delete ids and create a list of the key positions which should be deleted
    final IntList empty = new IntList(map.size());
    int p = -1;
    final int s = size();
    for(final byte[] key : allkeys) {
      p = get(key, ++p, s);
      if(p < 0) throw Util.notExpected("Tried to delete ids " + map.get(key) +
          " of non-existing index key: '" + string(key) + '\'');
      else if(deleteIds(p, key, map.get(key).sort().toArray()) == 0) empty.add(p);
    }

    // empty should contain sorted keys, since allkeys was sorted, too
    if(!empty.isEmpty()) deleteKeys(empty.finish());
  }

  /**
   * Remove record ids from the index.
   * @param ix index of the key
   * @param key record key
   * @param ids list of record ids to delete
   * @return number of remaining records
   */
  private int deleteIds(final int ix, final byte[] key, final int[] ids) {
    final long pos = idxr.read5(ix * 5L);
    final int numold = idxl.readNum(pos);

    if(numold == ids.length) {
      // all ids should be deleted: the key itself will be deleted, too
      cache.delete(key);
      return 0;
    }

    // read each id from the list and skip the ones which should be deleted
    // collect remaining values
    final int[] nids = new int[numold - ids.length];
    for(int i = 0, j = 0, cid = 0, pid = 0; i < nids.length;) {
      cid += idxl.readNum();
      if(j < ids.length && ids[j] == cid) ++j;
      else {
        nids[i++] = cid - pid;
        pid = cid;
      }
    }

    idxl.writeNums(pos, nids);

    // update the cache entry
    cache.add(key, nids.length, pos + Num.length(nids.length));

    return nids.length;
  }

  /**
   * Delete keys from the index.
   * @param keys list of key positions to delete
   */
  private void deleteKeys(final int[] keys) {
    // shift all keys to the left, skipping the ones which have to be deleted
    int j = 0;
    final int s = size();
    for(int pos = keys[j++], i = pos + 1; i < s; ++i) {
      if(j < keys.length && i == keys[j]) ++j;
      else {
        idxr.write5(pos * 5L, idxr.read5(i * 5L));
        ctext.put(pos++, ctext.get(i));
      }
    }
    // reduce the size of the index
    size(s - j);
  }

  @Override
  public synchronized void replace(final byte[] old, final byte[] key, final int id) {
    // delete the id from the old key
    final int p = get(old);
    if(p >= 0) {
      final int[] tmp = { id};
      if(deleteIds(p, old, tmp) == 0) {
        // the old key remains empty: delete it
        cache.delete(old);
        tmp[0] = p;
        deleteKeys(tmp);
      }
    }
    // add the id to the new key
    insertId(key, id);
  }

  /**
   * Add a text entry to the index.
   * @param key text to index
   * @param id id value
   */
  private void insertId(final byte[] key, final int id) {
    int ix = get(key);
    if(ix < 0) {
      ix = -(ix + 1);

      // shift all entries with bigger keys to the right
      final int s = size();
      for(int i = s; i > ix; --i)
        idxr.write5(i * 5L, idxr.read5((i - 1) * 5L));

      // add the key and the id
      idxr.write5(ix * 5L, idxl.appendNums(new int[] { id}));
      ctext.put(ix, key);
      // [DP] should the entry be added to the cache?

      size(s + 1);
    } else {
      // add id to the list of ids in the index node
      final long pos = idxr.read5(ix * 5L);
      final int num = idxl.readNum(pos);

      final int[] ids = new int[num + 1];
      boolean notadded = true;
      int cid = 0;
      for(int i = 0, j = -1; i < num; ++i) {
        int v = idxl.readNum();

        if(notadded && id < cid + v) {
          // add the new id
          ids[++j] = id - cid;
          notadded = false;
          // decrement the difference to the next id
          v -= id - cid;
          cid = id;
        }

        ids[++j] = v;
        cid += v;
      }

      if(notadded) ids[ids.length - 1] = id - cid;

      final long newpos = idxl.appendNums(ids);
      idxr.write5(ix * 5L, newpos);

      // update the cache entry
      cache.add(key, ids.length, newpos + Num.length(ids.length));
    }
  }

  /**
   * Assigns the number of index entries.
   * @param sz number of index entries
   */
  private void size(final int sz) {
    size.set(sz);
    idxl.write4(0, sz);
  }

  /**
   * Sort and calculate the differences between a list of ids.
   * @param ids id list
   * @return differences
   */
  private static int[] diffs(final IntList ids) {
    final int[] a = ids.sort().toArray();
    for(int l = a.length - 1; l > 0; --l) a[l] -= a[l - 1];
    return a;
  }
}
