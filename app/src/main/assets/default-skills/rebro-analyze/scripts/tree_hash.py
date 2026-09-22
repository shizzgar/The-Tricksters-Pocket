"""Hash every regular file and its relative name in a decoded tree."""
import argparse
import json
from artifacts import tree_snapshot

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tree')
    args = parser.parse_args()
    print(json.dumps(tree_snapshot(args.tree), ensure_ascii=False, indent=2))
