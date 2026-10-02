-- 관리자 기간 조회와 근무일/ID 정렬에 사용한다.
CREATE INDEX ix_attendance_work_date_id ON attendance (work_date, id);
