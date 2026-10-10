package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.dao.CommitmentDao
import com.baraa.masroof.data.room.mapper.CommitmentMapper
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.repository.CommitmentRepository

class RoomCommitmentRepository(
    private val dao: CommitmentDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : CommitmentRepository {
    override suspend fun create(commitment: Commitment): Unit = accessGate.withAccess<Unit> {
        dao.insert(CommitmentMapper.toEntity(commitment))
    }

    override suspend fun update(commitment: Commitment): Unit = accessGate.withAccess<Unit> {
        dao.update(CommitmentMapper.toEntity(commitment))
    }

    override suspend fun delete(id: String): Unit = accessGate.withAccess<Unit> {
        dao.delete(id)
    }

    override suspend fun get(id: String): Commitment? = accessGate.withAccess {
        dao.get(id)?.let(CommitmentMapper::toDomain)
    }

    override suspend fun getBySourceTransactionId(sourceTransactionId: String): Commitment? = accessGate.withAccess {
        dao.getBySourceTransactionId(sourceTransactionId)?.let(CommitmentMapper::toDomain)
    }

    override suspend fun listAll(): List<Commitment> = accessGate.withAccess {
        dao.listAll().map(CommitmentMapper::toDomain)
    }

    override suspend fun listActive(): List<Commitment> = accessGate.withAccess {
        dao.listActive().map(CommitmentMapper::toDomain)
    }

    companion object {
        fun from(database: MasroofDatabase, accessGate: DatabaseAccessGate = DatabaseAccessGate()): RoomCommitmentRepository =
            RoomCommitmentRepository(dao = database.commitmentDao())
    }
}
