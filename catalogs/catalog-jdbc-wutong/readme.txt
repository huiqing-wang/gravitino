gravitino REST API调用
-----------------------------------------------------------------------------------
一、metalake操作
1、创建metalake
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
-d '{"name":"test","comment":"Test metalake"}' http://localhost:8090/api/metalakes


二、catalog操作
1、创建catalog
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" -d '{
  "name": "wutong_catalog",
  "type": "RELATIONAL",
  "comment": "comment",
  "provider": "jdbc-wutong",
  "properties": {
    "jdbc-url": "jdbc:postgresql://192.168.94.131:5432/wutongdb",
    "jdbc-driver": "org.postgresql.Driver",
    "jdbc-database": "wutongdb",
    "jdbc-user": "postgres",
    "jdbc-password": "postgres"
  }
}' http://localhost:8090/api/metalakes/test/catalogs


2、list展示所有catalog
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs

curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs?details=true

3、查看某个catalog的详细信息
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog

4、修改catalog信息
curl -X PUT -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" -d '{
  "updates": [
    {
      "@type": "rename",
      "newName": "wutong_catalog"
    },
    {
      "@type": "setProperty",
      "property": "jdbc-user",
      "value": "postgres"
    }
  ]
}' http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog

5、删除catalog
#如果该catalog下有schema、table等信息，需要先用restApi删除这些表（如果 cascade 为 true，会删除真实的schema、表)
curl -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/public?cascade=true

#然后才能删除catalog（不删除数据对应的真实的database）
curl -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog


三、schema操作
1、创建schema---postgresql不支持properties: 会在postgresql数据库中create scheam。
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" -d '{
  "name": "test_schema",
  "comment": "comment"
}' http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas

2、list某个catalog下所有的schema
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas

3、查看schema详细信息
curl -X GET \-H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/test_schema

4、修改schema信息: postgresql定义schema不支持自定义Properties
curl -X PUT -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" -d '{
  "updates": [
    {
      "@type": "removeProperty",
      "property": "key2"
    }, {
      "@type": "setProperty",
      "property": "key3",
      "value": "value3"
    }
  ]
}' http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/schema

5、删除schema：
curl -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/public?cascade=true

四、表操作
1、创建表table: Doesn't support table properties.
curl -X POST -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" -d '{
  "name": "example_table",
  "comment": "This is an example table",
  "columns": [
    {
      "name": "id",
      "type": "integer",
      "comment": "id column comment",
      "nullable": false,
      "autoIncrement": true
    },
    {
      "name": "name",
      "type": "varchar(500)",
      "comment": "name column comment",
      "nullable": true,
      "autoIncrement": false,
      "defaultValue": {
        "type": "literal",
        "dataType": "null",
        "value": "null"
      }
    },
    {
      "name": "age",
      "type": "integer",
      "comment": "age column comment",
      "nullable": false,
      "autoIncrement": false,
      "defaultValue": {
        "type": "literal",
        "dataType": "integer",
        "value": "-1"
      }
    },
    {
      "name": "dt",
      "type": "date",
      "comment": "dt column comment",
      "nullable": true
    }
  ],
  "indexes": [
    {
      "indexType": "primary_key",
      "name": "PRIMARY",
      "fieldNames": [["id"]]
    }
  ]
}' http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/test_schema/tables

2、list某个schema下的所有table
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/test_schema/tables

3、查看某个table的详细信息
curl -X GET -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json"  \
http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/test_schema/tables/example_table


4、修改table信息
curl -X PUT -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" -d '{
  "updates": [
    {"@type":"rename","newName":"table_renamed"}
  ]  
}' http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/test_schema/tables/example_table

5、删除table
## Purge can be true or false, if purge is true, Gravitino will remove the data from the table.

curl -X DELETE -H "Accept: application/vnd.gravitino.v1+json" \
-H "Content-Type: application/json" \
http://localhost:8090/api/metalakes/test/catalogs/wutong_catalog/schemas/test_schema/tables/table_renamed?purge=false

删除表有两种方法：dropTable 和 purgeTable：
>>如果表是内部表，dropTable 会从文件系统中删除与表关联的元数据和目录。如果是外部表，则仅删除关联的元数据。
>>purgeTable 会完全删除与表关联的元数据和directory并跳过trash。如果表是外部表或directory不支持purge表，则会抛出 UnsupportedOperationException。
>>Hive catalog 和 lakehouse-iceberg catalog 支持 purgeTable，而 jdbc-mysql、jdbc-postgresql 和 lakehouse-paimon catalog 不支持。
