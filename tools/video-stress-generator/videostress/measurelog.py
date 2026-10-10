"""Disk-backed exact quantiles, keeping scalar analysis memory bounded too."""

import math
import sqlite3


class MeasureLog:
    def __init__(self,path):
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA cache_size=-2048")
        self.db.execute("PRAGMA temp_store=FILE")
        self.db.execute("PRAGMA journal_mode=OFF")
        self.db.execute("CREATE TABLE measurements (segment TEXT, si REAL, ti REAL, delta REAL, entropy REAL, boundary INTEGER)")

    def append(self,segment,row):
        self.db.execute("INSERT INTO measurements VALUES (?,?,?,?,?,?)",
                        (segment,row["si"],row["ti"],row["mean_absolute_delta"],row["entropy_bits"],bool(row.get("boundary"))))

    def stats(self,column,segment=None,exclude=False):
        if column not in ("si","ti","delta","entropy"):
            raise ValueError("Unknown metric column")
        where,parameters = f"{column} IS NOT NULL",[]
        if segment is not None:
            where += " AND segment=?"
            parameters.append(segment)
        if exclude:
            where += " AND boundary=0"
        count,peak = self.db.execute(f"SELECT COUNT(*),MAX({column}) FROM measurements WHERE {where}",parameters).fetchone()
        if not count:
            return {"status":"UNMEASURED","count":0,"median":None,"p95":None,"peak":None}
        def quantile(q):
            index = (count-1)*q
            lo,hi = math.floor(index),math.ceil(index)
            a = self.db.execute(f"SELECT {column} FROM measurements WHERE {where} ORDER BY {column} LIMIT 1 OFFSET ?",[*parameters,lo]).fetchone()[0]
            b = self.db.execute(f"SELECT {column} FROM measurements WHERE {where} ORDER BY {column} LIMIT 1 OFFSET ?",[*parameters,hi]).fetchone()[0]
            return a+(b-a)*(index-lo)
        return {"status":"MEASURED","count":count,"median":quantile(0.5),"p95":quantile(0.95),"peak":peak}

    def summary(self,segment=None):
        return {"si":self.stats("si",segment),"ti_including_cuts_seams":self.stats("ti",segment),
                "ti_excluding_cuts_seams":self.stats("ti",segment,True),
                "mean_absolute_delta":self.stats("delta",segment),"entropy_bits":self.stats("entropy",segment)}

    def close(self):
        self.db.close()
