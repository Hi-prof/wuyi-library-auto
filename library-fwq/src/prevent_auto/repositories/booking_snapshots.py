"""``booking_snapshots`` 表的访问层。

号池管理页活跃池 Tab 用这张表作为「上次检测时」的预约视图来源，避免每次打开
页面都去打学校接口造成卡顿。每次状态检测（``MonitorLoop._run_account_cycle``）
把 ``bridge.fetch_bookings`` 的全量结果按账号整体替换写入；写入路径同时收敛了
今天 / 明天 / 后天的全部预约，与号池页 active Tab 的渲染口径对齐。
"""

from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path

from prevent_auto.database import connect_database
from prevent_auto.models import BookingSnapshot


class BookingSnapshotsRepository:
    def __init__(self, database_path: str | Path) -> None:
        self.database_path = Path(database_path)

    def replace_for_account(
        self,
        *,
        account_id: int,
        bookings: Iterable[BookingSnapshot],
        refreshed_at: str,
    ) -> None:
        """把 ``account_id`` 名下的快照行整体替换成 ``bookings``。

        单事务内 ``DELETE + INSERT``，保证号池页读取时不会看到「半旧半新」的
        混合状态。空列表合法（账号当前没有任何预约），等同于清空该账号缓存。
        """

        rows = [
            (
                account_id,
                booking.booking_id,
                booking.room_name,
                booking.seat_number,
                booking.status,
                int(booking.start_time),
                int(max(booking.duration_seconds, 0)),
                booking.checkin_deadline_at,
                refreshed_at,
            )
            for booking in bookings
        ]
        with connect_database(self.database_path) as connection:
            connection.execute(
                "DELETE FROM booking_snapshots WHERE account_id = ?",
                (account_id,),
            )
            if rows:
                connection.executemany(
                    """
                    INSERT INTO booking_snapshots (
                        account_id, booking_id, room_name, seat_number,
                        status, start_time, duration_seconds,
                        checkin_deadline_at, refreshed_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    rows,
                )

    def list_by_account_ids(
        self,
        account_ids: Iterable[int],
    ) -> dict[int, list[BookingSnapshot]]:
        """按账号 id 批量取快照，返回 ``{account_id: [BookingSnapshot, ...]}``。

        没有任何快照行的账号不会出现在结果字典里，调用方按 ``dict.get`` 兜底。
        ``start_time`` 倒序与 :meth:`WuyiBridge.fetch_bookings` 的返回口径保持一致。
        """

        ids = [int(account_id) for account_id in account_ids]
        if not ids:
            return {}
        placeholders = ",".join("?" for _ in ids)
        query = (
            "SELECT account_id, booking_id, room_name, seat_number, status, "
            "start_time, duration_seconds, checkin_deadline_at "
            "FROM booking_snapshots "
            f"WHERE account_id IN ({placeholders}) "
            "ORDER BY account_id, start_time DESC"
        )
        with connect_database(self.database_path) as connection:
            rows = connection.execute(query, ids).fetchall()
        result: dict[int, list[BookingSnapshot]] = {}
        for row in rows:
            account_id = int(row["account_id"])
            result.setdefault(account_id, []).append(
                BookingSnapshot(
                    booking_id=str(row["booking_id"]),
                    room_name=str(row["room_name"]),
                    seat_number=str(row["seat_number"]),
                    status=str(row["status"]),
                    start_time=int(row["start_time"]),
                    duration_seconds=int(row["duration_seconds"]),
                    checkin_deadline_at=(
                        None
                        if row["checkin_deadline_at"] is None
                        else int(row["checkin_deadline_at"])
                    ),
                )
            )
        return result
