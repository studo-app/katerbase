import com.mongodb.client.model.Indexes
import com.moshbit.katerbase.*
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import util.addYears
import util.forEachAsync
import java.util.*
import kotlin.reflect.KClass

class DatabaseTests {

  class EnumMongoPayload : MongoMainEntry() {
    enum class Enum1 {
      VALUE1, VALUE2, VALUE3
    }

    var value1 = Enum1.VALUE1
    var enumList: List<Enum1> = emptyList()
    var date = Date()
    var long = 0L
    var stringList: List<String> = emptyList()
    val computedProp get() = value1 == Enum1.VALUE1
    val staticProp = value1 == Enum1.VALUE1
    var double = 0.0
    var map: Map<String, String> = mapOf()
    var byteArray: ByteArray = "yolo".toByteArray()
    var dateArray = emptyList<Date>()
  }

  class SimpleMongoPayload : MongoMainEntry() {
    var double = 0.0
    var stringList: List<String> = emptyList()
  }

  @Test
  fun enumHandling1() {
    val payload = EnumMongoPayload().apply { _id = "testId" }
    testDb.getCollection<EnumMongoPayload>().insertOne(payload, upsert = true)

    val results = testDb.getCollection<EnumMongoPayload>().find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE1)
    assert(results.toList().isNotEmpty())
    testDb.getCollection<EnumMongoPayload>().deleteOne(EnumMongoPayload::_id equal "testId")
  }

  @Test
  fun enumHandling2() {
    val payload = EnumMongoPayload().apply { _id = "testId" }
    testDb.getCollection<EnumMongoPayload>().insertOne(payload, upsert = true)

    testDb.getCollection<EnumMongoPayload>().updateOne(EnumMongoPayload::_id equal "testId") {
      EnumMongoPayload::value1 setTo EnumMongoPayload.Enum1.VALUE2
    }

    val results = testDb.getCollection<EnumMongoPayload>().find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE2)
    assert(results.toList().isNotEmpty())

    testDb.getCollection<EnumMongoPayload>().deleteOne(EnumMongoPayload::_id equal "testId")
  }

  @Test
  fun faultyEnumList() {
    // Katerbase will print those 2 warnings on stdout:
    // Array enumList in EnumMongoPayload contains null, but is a non-nullable collection: _id=faultyEnumList
    // Enum value FAULTY of type Enum1 doesn't exists any more but still present in database: EnumMongoPayload, _id=faultyEnumList

    testDb.getCollection<EnumMongoPayload>().deleteOne(EnumMongoPayload::_id equal "faultyEnumList")
    testDb.getCollection<EnumMongoPayload>().collection.insertOne(
      Document(
        listOf(
          "_id" to "faultyEnumList",
          "enumList" to listOf(
            "VALUE1", "FAULTY", null, "VALUE3"
          )
        ).toMap()
      )
    )

    val result = testDb.getCollection<EnumMongoPayload>().findOne(EnumMongoPayload::_id equal "faultyEnumList")

    assert(result!!.enumList.size == 2)
    assert(result.enumList[0] == EnumMongoPayload.Enum1.VALUE1)
    assert(result.enumList[1] == EnumMongoPayload.Enum1.VALUE3)
  }

  @Test
  fun dateDeserialization() {
    val payload = EnumMongoPayload().apply { _id = "datetest" }
    testDb.getCollection<EnumMongoPayload>().let { coll ->
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assert(res.date.toString() == payload.date.toString())
    }
  }

  @Test
  fun customByteArrayDeserialization1() {
    val payload = EnumMongoPayload().apply { _id = "bytetest" }
    testDb.getCollection<EnumMongoPayload>().let { coll ->
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assertEquals(String(payload.byteArray), String(res.byteArray))
      assertEquals(payload.byteArray.size, res.byteArray.size)
    }
  }

  @Test
  fun customByteArrayDeserialization2() {
    val payload = EnumMongoPayload().apply { _id = "bytetest"; byteArray = "yo 😍 \u0000 😄".toByteArray() }
    testDb.getCollection<EnumMongoPayload>().let { coll ->
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assertEquals(String(payload.byteArray), String(res.byteArray))
      assertEquals(payload.byteArray.size, res.byteArray.size)
    }
  }

  class CustomDateArrayCLass {
    val array = listOf(Date(), Date().addYears(-1), Date().addYears(-10))
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun customDateArrayTest() {
    val payload = CustomDateArrayCLass()
    val bson = JsonHandler.toBsonDocument(payload)
    val newPayload = JsonHandler.fromBson(bson, CustomDateArrayCLass::class)

    (payload.array zip (bson["array"] as List<Date>)).forEach { (old, new) -> assert(old == new) }
    (payload.array zip newPayload.array).forEach { (old, new) -> assert(old == new) }
  }

  @Test
  fun multithreadedFindOneOrCreate() {
    val id = "multicreateid"
    val value = EnumMongoPayload.Enum1.VALUE2
    var createNewCalls = 0
    testDb.getCollection<EnumMongoPayload>().drop()
    (1..50).forEachAsync {
      (1..500).forEach {
        val result = testDb.getCollection<EnumMongoPayload>().findOneOrCreate(EnumMongoPayload::_id equal id) {
          createNewCalls++
          EnumMongoPayload().apply { this.value1 = value }
        }
        assert(result._id == id)
        assert(result.value1 == value)
      }
    }
    println("Called create $createNewCalls times")
  }

  @Test
  fun primitiveList() {
    val id = "primitiveTest"
    val payload = EnumMongoPayload().apply { stringList = listOf("a", "b"); _id = id }

    testDb.getCollection<EnumMongoPayload>().insertOne(payload, upsert = true)
    var retrievedPayload = testDb.getCollection<EnumMongoPayload>().findOne(EnumMongoPayload::_id equal id)!!
    assert(payload.stringList == retrievedPayload.stringList)

    testDb.getCollection<EnumMongoPayload>().updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::stringList setTo listOf("c", "d")
    }
    retrievedPayload = testDb.getCollection<EnumMongoPayload>().findOne(EnumMongoPayload::_id equal id)!!

    assert(listOf("c", "d") == retrievedPayload.stringList)
  }

  @Test
  fun persistencyTest() {
    val range = (1..10000)
    val collection = testDb.getCollection<EnumMongoPayload>()
    val idPrefix = "persistencyTest"

    range.forEach { index ->
      val id = idPrefix + index
      collection.deleteOne(EnumMongoPayload::_id equal id)
    }

    range.forEachAsync { index ->
      val id = idPrefix + index
      collection.deleteOne(EnumMongoPayload::_id equal id)
      collection.insertOne(EnumMongoPayload().apply { _id = id }, upsert = false)
      assert(collection.findOne(EnumMongoPayload::_id equal id) != null)
      collection.deleteOne(EnumMongoPayload::_id equal id)
    }

    range.forEachAsync { index ->
      val id = idPrefix + index
      collection.deleteOne(EnumMongoPayload::_id equal id)
      collection.findOneOrCreate(EnumMongoPayload::_id equal id) { EnumMongoPayload() }
      assert(collection.findOne(EnumMongoPayload::_id equal id) != null)
      collection.deleteOne(EnumMongoPayload::_id equal id)
    }
  }

  @Test
  fun computedPropTest() {
    val payload = EnumMongoPayload()
    val bson = payload.toBSONDocument()
    assert(bson["computedProp"] == null)
    assertEquals(11, bson.size)
  }
/*
  @Test
  fun nullListTest() {
    val raw = """{"_id":"","value1":"VALUE1","enumList":[],"date":"2017-08-23T14:52:30.252+02","stringList":["test1", null, "test2"],"staticProp":true,"computedProp":true}"""
    val payload: EnumMongoPayload = JsonHandler.fromJson(raw)
    assert(payload.stringList.size == 2)
  }*/

  @Test
  fun testInfinity() {
    val collection = testDb.getCollection<EnumMongoPayload>().apply { drop() }
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityA"; double = Double.POSITIVE_INFINITY }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityB"; double = Double.MAX_VALUE }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityC"; double = Double.MIN_VALUE }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityD"; double = 0.0 }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityE"; double = Double.NEGATIVE_INFINITY }, upsert = false)
    collection.insertOne(EnumMongoPayload().apply { _id = "testInfinityF"; double = Double.NaN }, upsert = false)

    assert(collection.find().count() == 6)
    assert(collection.find(EnumMongoPayload::double equal Double.POSITIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.MAX_VALUE).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.MIN_VALUE).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal 0.0).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.NEGATIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double equal Double.NaN).count() == 1)

    assert(collection.find(EnumMongoPayload::double lowerEquals Double.POSITIVE_INFINITY).count() == 5)
    assert(collection.find(EnumMongoPayload::double lower Double.POSITIVE_INFINITY).count() == 4)
    assert(collection.find(EnumMongoPayload::double lowerEquals Double.MAX_VALUE).count() == 4)
    assert(collection.find(EnumMongoPayload::double lower Double.MAX_VALUE).count() == 3)
    assert(collection.find(EnumMongoPayload::double lower 1000.0).count() == 3)
    assert(collection.find(EnumMongoPayload::double lowerEquals Double.MIN_VALUE).count() == 3)
    assert(collection.find(EnumMongoPayload::double lower Double.MIN_VALUE).count() == 2)
    assert(collection.find(EnumMongoPayload::double lowerEquals 0.0).count() == 2)
    assert(collection.find(EnumMongoPayload::double lower 0.0).count() == 1)
    assert(collection.find(EnumMongoPayload::double lower -1000.0).count() == 1)
    assert(collection.find(EnumMongoPayload::double lowerEquals Double.NEGATIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double lower Double.NEGATIVE_INFINITY).count() == 0)

    assert(collection.find(EnumMongoPayload::double greater Double.POSITIVE_INFINITY).count() == 0)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.POSITIVE_INFINITY).count() == 1)
    assert(collection.find(EnumMongoPayload::double greater Double.MAX_VALUE).count() == 1)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.MAX_VALUE).count() == 2)
    assert(collection.find(EnumMongoPayload::double greater 1000.0).count() == 2)
    assert(collection.find(EnumMongoPayload::double greater Double.MIN_VALUE).count() == 2)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.MIN_VALUE).count() == 3)
    assert(collection.find(EnumMongoPayload::double greater 0.0).count() == 3)
    assert(collection.find(EnumMongoPayload::double greaterEquals 0.0).count() == 4)
    assert(collection.find(EnumMongoPayload::double greater -1000.0).count() == 4)
    assert(collection.find(EnumMongoPayload::double greater Double.NEGATIVE_INFINITY).count() == 4)
    assert(collection.find(EnumMongoPayload::double greaterEquals Double.NEGATIVE_INFINITY).count() == 5)
  }

  @Test
  fun unsetTest() {
    val collection = testDb.getCollection<EnumMongoPayload>().apply { drop() }
    val id = "unsetTest"
    collection.insertOne(document = EnumMongoPayload().apply { _id = id }, upsert = false)

    fun put(key: String) = collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::map.child(key) setTo key
    }

    fun remove(key: String) = collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::map.child(key).unset()
    }

    fun get() = collection.findOne(EnumMongoPayload::_id equal id)!!.map

    assert(get().isEmpty())

    (1..10).forEach { put(it.toString()) }
    get().let { map -> (1..10).map { it.toString() }.forEach { assert(map[it] == it) } }

    (1..5).forEach { remove(it.toString()) }
    get().let { map -> (6..10).map { it.toString() }.forEach { assert(map[it] == it) } }

    collection.updateOne(EnumMongoPayload::_id equal id) {
      EnumMongoPayload::map.unset()
    }

    assert(get().isEmpty())
  }

  @Test
  fun distinctTest() {
    val collection = testDb.getCollection<EnumMongoPayload>().apply { drop() }
    val id = "distinctTest"

    (0..100).forEach { index ->
      collection.insertOne(EnumMongoPayload().apply {
        _id = "$id-$index-first"
        this.double = index.toDouble()
      }, upsert = false)
      collection.insertOne(EnumMongoPayload().apply {
        _id = "$id-$index-second"
        this.double = index.toDouble()
      }, upsert = false)
    }

    val distinctValues = collection.distinct(EnumMongoPayload::double).toList()

    assert(distinctValues.distinct().count() == distinctValues.count())
  }

  @Test
  fun equalsTest() {
    val collection1 = testDb.getCollection<EnumMongoPayload>().apply { drop() }
    val collection2 = testDb.getCollection<SimpleMongoPayload>().apply { drop() }


    // Find
    val cursor1 = collection1.find()
    val cursor1b = collection1.find()
    val cursor2 = collection2.find()

    // Different collection
    assert(cursor1 != cursor2)
    assert(cursor1.hashCode() != cursor2.hashCode())

    // Same collection, same cursor
    assert(cursor1 == cursor1b)
    assert(cursor1.hashCode() == cursor1b.hashCode())


    // Distinct
    val distinct1 = collection1.distinct(EnumMongoPayload::double)
    //val distinct1b = collection1.distinct(EnumMongoPayload::double)
    val distinct2 = collection2.distinct(SimpleMongoPayload::double)

    // Different collection
    assert(distinct1 != distinct2)
    assert(distinct1.hashCode() != distinct2.hashCode())

    // We don't have equals/hashCode for distinct
    //assert(distinct1 == distinct1b)
    //assert(distinct1.hashCode() == distinct1b.hashCode())
  }

  @Test
  fun longTest() {
    val payload = EnumMongoPayload().apply { _id = "longTest" }

    // 0
    (-100L..100L).forEach { long ->
      testDb.getCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // INT_MIN
    (Int.MIN_VALUE.toLong() - 100L..Int.MIN_VALUE.toLong() + 100L).forEach { long ->
      testDb.getCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // INT_MAX
    (Int.MAX_VALUE.toLong() - 100L..Int.MAX_VALUE.toLong() + 100L).forEach { long ->
      testDb.getCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // LONG_MIN
    (Long.MIN_VALUE..Long.MIN_VALUE + 100L).forEach { long ->
      testDb.getCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }

    // LONG_MAX
    (Long.MAX_VALUE - 100L..Long.MAX_VALUE).forEach { long ->
      testDb.getCollection<EnumMongoPayload>().let { coll ->
        payload.long = long
        coll.insertOne(payload, upsert = true)
        val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
        assertEquals(long, res.long)
      }
    }
  }

  @Test
  fun dateArrayTest() {
    val payload = EnumMongoPayload().apply { _id = "dateArrayTest" }

    testDb.getCollection<EnumMongoPayload>().let { coll ->
      payload.dateArray = listOf(Date(), Date().addYears(1), Date().addYears(10))
      coll.insertOne(payload, upsert = true)
      val res = coll.findOne(EnumMongoPayload::_id equal payload._id)!!
      assertEquals(payload.dateArray[0], res.dateArray[0])
      assertEquals(payload.dateArray[1], res.dateArray[1])
      assertEquals(payload.dateArray[2], res.dateArray[2])
      assertEquals(payload.dateArray.size, res.dateArray.size)
    }
  }

  @Test
  fun hintTest() {
    testDb.getCollection<EnumMongoPayload>()
      .find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE1)
      .hint("value1_1_date_1")
  }

  @Test
  fun invalidHintTest() {
    assertThrows(IllegalArgumentException::class.java) {
      testDb.getCollection<EnumMongoPayload>()
        .find(EnumMongoPayload::value1 equal EnumMongoPayload.Enum1.VALUE1)
        .hint("value1_1_date_-1")
    }
  }

  companion object {
    lateinit var testDb: MongoDatabase

    @Suppress("unused")
    @BeforeAll
    @JvmStatic
    fun setup() {
      testDb = object : MongoDatabase("mongodb://localhost:27017/local") {

        override fun getCappedCollectionsMaxBytes(): Map<out KClass<out MongoMainEntry>, Long> = emptyMap()

        override fun getCollections(): Map<out KClass<out MongoMainEntry>, String> = mapOf(
          EnumMongoPayload::class to "enumColl",
          SimpleMongoPayload::class to "simpleMongoColl"
        )


        override fun getIndexes() {
          getCollection<EnumMongoPayload>().createIndex(EnumMongoPayload::value1.toMongoField().ascending())
          getCollection<EnumMongoPayload>().createIndex(
            Indexes.compoundIndex(
              EnumMongoPayload::value1.toMongoField().ascending(),
              EnumMongoPayload::date.toMongoField().ascending()
            )
          )
        }
      }
    }
  }
}