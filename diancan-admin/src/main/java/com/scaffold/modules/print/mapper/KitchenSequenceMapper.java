package com.scaffold.modules.print.mapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
@Mapper public interface KitchenSequenceMapper {
 @Insert("INSERT INTO kitchen_sequence(sequence_date,last_number) VALUES(#{date},0) ON DUPLICATE KEY UPDATE last_number=last_number") void ensure(@Param("date")LocalDate date);
 @Select("SELECT COALESCE(MAX(last_number),0) FROM kitchen_sequence") Integer largestAllocated();
 @Select("SELECT last_number FROM kitchen_sequence WHERE sequence_date=#{date} FOR UPDATE") Integer lockedValue(@Param("date")LocalDate date);
 @Update("UPDATE kitchen_sequence SET last_number=#{number} WHERE sequence_date=#{date}") void setValue(@Param("date")LocalDate date,@Param("number")int number);
}
