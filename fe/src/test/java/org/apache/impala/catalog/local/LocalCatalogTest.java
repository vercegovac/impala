// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.catalog.local;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.apache.impala.catalog.CatalogTest;
import org.apache.impala.catalog.FeCatalogUtils;
import org.apache.impala.catalog.FeDb;
import org.apache.impala.catalog.FeFsPartition;
import org.apache.impala.catalog.FeFsTable;
import org.apache.impala.catalog.FeTable;
import org.apache.impala.catalog.HdfsPartition.FileDescriptor;
import org.apache.impala.catalog.Type;
import org.apache.impala.thrift.TResultSet;
import org.apache.impala.util.MetaStoreUtil;
import org.apache.impala.util.PatternMatcher;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

public class LocalCatalogTest {

  private LocalCatalog catalog_;

  @Before
  public void setupCatalog() {
    catalog_ = new LocalCatalog(new DirectMetaProvider());
  }

  @Test
  public void testDbs() throws Exception {
    FeDb functionalDb = catalog_.getDb("functional");
    assertNotNull(functionalDb);
    FeDb functionalSeqDb = catalog_.getDb("functional_seq");
    assertNotNull(functionalSeqDb);

    Set<FeDb> dbs = ImmutableSet.copyOf(
        catalog_.getDbs(PatternMatcher.MATCHER_MATCH_ALL));
    assertTrue(dbs.contains(functionalDb));
    assertTrue(dbs.contains(functionalSeqDb));

    dbs = ImmutableSet.copyOf(
        catalog_.getDbs(PatternMatcher.createHivePatternMatcher("*_seq")));
    assertFalse(dbs.contains(functionalDb));
    assertTrue(dbs.contains(functionalSeqDb));
  }

  @Test
  public void testListTables() throws Exception {
    Set<String> names = ImmutableSet.copyOf(catalog_.getTableNames(
        "functional", PatternMatcher.MATCHER_MATCH_ALL));
    assertTrue(names.contains("alltypes"));

    FeDb db = catalog_.getDb("functional");
    assertNotNull(db);
    FeTable t = catalog_.getTable("functional", "alltypes");
    assertNotNull(t);
    assertSame(t, db.getTable("alltypes"));
    assertSame(db, t.getDb());
    assertEquals("alltypes", t.getName());
    assertEquals("functional", t.getDb().getName());
    assertEquals("functional.alltypes", t.getFullName());
  }

  @Test
  public void testLoadTableBasics() throws Exception {
    FeDb functionalDb = catalog_.getDb("functional");
    CatalogTest.checkTableCols(functionalDb, "alltypes", 2,
        new String[]
          {"year", "month", "id", "bool_col", "tinyint_col", "smallint_col",
           "int_col", "bigint_col", "float_col", "double_col", "date_string_col",
           "string_col", "timestamp_col"},
        new Type[]
          {Type.INT, Type.INT, Type.INT,
           Type.BOOLEAN, Type.TINYINT, Type.SMALLINT,
           Type.INT, Type.BIGINT, Type.FLOAT,
           Type.DOUBLE, Type.STRING, Type.STRING,
           Type.TIMESTAMP});
    FeTable t = functionalDb.getTable("alltypes");
    assertEquals(7300, t.getNumRows());

    assertTrue(t instanceof LocalFsTable);
    FeFsTable fsTable = (FeFsTable) t;
    assertEquals(MetaStoreUtil.DEFAULT_NULL_PARTITION_KEY_VALUE,
        fsTable.getNullPartitionKeyValue());

    // Stats should have one row per partition, plus a "total" row.
    TResultSet stats = fsTable.getTableStats();
    assertEquals(25, stats.getRowsSize());
  }

  @Test
  public void testPartitioning() throws Exception {
    FeFsTable t = (FeFsTable) catalog_.getTable("functional",  "alltypes");
    // TODO(todd): once we support file descriptors in LocalCatalog,
    // run the full test.
    CatalogTest.checkAllTypesPartitioning(t, /*checkFileDescriptors=*/false);
  }

  /**
   * Test that partitions with a NULL value can be properly loaded.
   */
  @Test
  public void testEmptyPartitionValue() throws Exception {
    FeFsTable t = (FeFsTable) catalog_.getTable("functional",  "alltypesagg");
    // This table has one partition with a NULL value for the 'day'
    // clustering column.
    int dayCol = t.getColumn("day").getPosition();
    Set<Long> ids = t.getNullPartitionIds(dayCol);
    assertEquals(1,  ids.size());
    FeFsPartition partition = FeCatalogUtils.loadPartition(
        t, Iterables.getOnlyElement(ids));
    assertTrue(partition.getPartitionValue(dayCol).isNullLiteral());
  }

  @Test
  public void testLoadFileDescriptors() throws Exception {
    FeFsTable t = (FeFsTable) catalog_.getTable("functional",  "alltypes");
    int totalFds = 0;
    for (FeFsPartition p: FeCatalogUtils.loadAllPartitions(t)) {
      List<FileDescriptor> fds = p.getFileDescriptors();
      totalFds += fds.size();
      for (FileDescriptor fd : fds) {
        assertTrue(fd.getFileLength() > 0);
        assertEquals(fd.getNumFileBlocks(), 1);
        assertEquals(3, fd.getFbFileBlock(0).diskIdsLength());
      }
    }
    assertEquals(24, totalFds);
  }
}
