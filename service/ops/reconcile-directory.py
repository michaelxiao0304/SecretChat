"""Verify the independent revocation journal, then reconcile an offline directory restore."""
import argparse
import hashlib
import re
import subprocess
import sys

JOURNAL = """SELECT account_id,device_id,action,occurred_ms
FROM sc_ledger.revocations ORDER BY device_id"""
REPLAY = """
START TRANSACTION;
DELETE d FROM sc_directory.devices d
JOIN sc_ledger.revocations r ON d.device_id=r.device_id;
DELETE a FROM sc_directory.accounts a
JOIN sc_ledger.revocations r ON a.account_id=r.account_id AND r.action='ACCOUNT_CLOSED';
DELETE FROM sc_directory.email_challenges;
DELETE FROM sc_directory.invites WHERE redeemed_by IS NULL;
UPDATE sc_directory.one_time_prekeys SET material=NULL WHERE material IS NOT NULL;
UPDATE sc_directory.invite_settings SET bootstrap_used=EXISTS(SELECT 1 FROM sc_directory.accounts)
WHERE settings_id=1;
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--defaults-file", required=True, help="MySQL maintenance client configuration")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--checkpoint", action="store_true", help="Print final digest while all writers are offline")
    mode.add_argument("--replay", metavar="FINAL_SHA256", help="Trusted final digest stored outside the directory backup")
    args = parser.parse_args()
    mysql = [args.mysql, "--defaults-file=" + args.defaults_file, "--database=sc_directory",
             "--batch", "--raw", "--skip-column-names"]
    result = subprocess.run(mysql + ["-e", JOURNAL], stdout=subprocess.PIPE, check=True)
    digest = hashlib.sha256(result.stdout).hexdigest()
    if args.checkpoint:
        print(digest)
        return
    if not re.fullmatch("[a-f0-9]{64}", args.replay) or digest != args.replay:
        sys.exit("Revocation journal differs from the final trusted checkpoint. Restore stopped.")
    subprocess.run(mysql, input=REPLAY.encode(), check=True)
    print("Directory reconciled.")


if __name__ == "__main__":
    main()
