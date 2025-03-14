#!/bin/bash

if [ ! $# = 1 ]
then
	echo "sh `basename $0` file_name"
	exit -1
fi

SQL_FILE=$1
file_dir=$(dirname "$SQL_FILE")
OUT_FILE=$file_dir/out.sql

function conv()
{
  local str=$1
  tmp=`echo $str|awk -F "VALUES" '{print $1}'`
  str1=$(echo "$tmp" | tr -d '"')
  str2=`echo $str|awk -F "VALUES" '{print $2}'`
  echo "$str1 VALUES $str2"
}

while IFS= read -r line; do
  line=$(echo "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')

  # 如果当前行为空，跳过
  if [[ -z "$line" ]]; then
    continue
  fi

  # 将当前行追加到缓冲区
  buffer+="$line"

  # 检查缓冲区的最后一个字符是否是分号
  if [[ "${buffer: -1}" == ";" ]]; then
    # 如果是分号，将缓冲区内容写入输出文件
    if [[ "$buffer" =~ ^INSERT ]]; then
      result=`conv "$buffer"`
      echo "$result" >> "$OUT_FILE"
    else
      buffer=""
      continue
    fi
    buffer=""
  else
    buffer+=" "
  fi
done < "$SQL_FILE"