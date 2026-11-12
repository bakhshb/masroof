package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.dao.UserCorrectionDao
import com.baraa.masroof.data.room.mapper.UserCorrectionMapper
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.repository.UserCorrectionRepository

class RoomUserCorrectionRepository(
    private val dao: UserCorrectionDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : UserCorrectionRepository {
    override suspend fun save(correction: UserCorrection): Unit = accessGate.withAccess<Unit> {
        dao.insert(UserCorrectionMapper.toEntity(correction))
    }

    override suspend fun listAll(): List<UserCorrection> = accessGate.withAccess {
        dao.listAll().map(UserCorrectionMapper::toDomain)
    }
}
